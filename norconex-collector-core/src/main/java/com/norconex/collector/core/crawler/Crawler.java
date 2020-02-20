/* Copyright 2014-2020 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.collector.core.crawler;

import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_CLEAN_BEGIN;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_CLEAN_END;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_INIT_BEGIN;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_INIT_END;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_RUN_BEGIN;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_RUN_END;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_STOP_BEGIN;
import static com.norconex.collector.core.crawler.CrawlerEvent.CRAWLER_STOP_END;
import static com.norconex.collector.core.crawler.CrawlerEvent.DOCUMENT_COMMITTED_REMOVE;
import static com.norconex.collector.core.crawler.CrawlerEvent.DOCUMENT_IMPORTED;
import static com.norconex.collector.core.crawler.CrawlerEvent.REJECTED_ERROR;
import static com.norconex.collector.core.crawler.CrawlerEvent.REJECTED_IMPORT;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.collector.core.Collector;
import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.crawler.CrawlerConfig.OrphansStrategy;
import com.norconex.collector.core.doc.CrawlDocInfo;
import com.norconex.collector.core.doc.CrawlDocInfoService;
import com.norconex.collector.core.doc.CrawlDocMetadata;
import com.norconex.collector.core.doc.CrawlState;
import com.norconex.collector.core.jmx.Monitoring;
import com.norconex.collector.core.pipeline.importer.ImporterPipelineContext;
import com.norconex.collector.core.spoil.ISpoiledReferenceStrategizer;
import com.norconex.collector.core.spoil.SpoiledReferenceStrategy;
import com.norconex.collector.core.spoil.impl.GenericSpoiledReferenceStrategizer;
import com.norconex.collector.core.store.DataStoreExporter;
import com.norconex.collector.core.store.DataStoreImporter;
import com.norconex.collector.core.store.IDataStoreEngine;
import com.norconex.committer.core.ICommitter;
import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.time.DurationFormatter;
import com.norconex.importer.Importer;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.response.ImporterResponse;
import com.norconex.jef5.job.AbstractResumableJob;
import com.norconex.jef5.status.JobStatus;
import com.norconex.jef5.status.JobStatusUpdater;
import com.norconex.jef5.suite.JobSuite;

/**
 * <p>Abstract crawler implementation providing a common base to building
 * crawlers.</p>
 *
 * <p>As of 1.6.1, JMX support is disabled by default.  To enable it,
 * set the system property "enableJMX" to <code>true</code>.  You can do so
 * by adding this to your Java launch command:
 * </p>
 * <pre>
 *     -DenableJMX=true
 * </pre>
 *
 * @author Pascal Essiembre
 */
//TODO document that logger should print thread name to see which crawler
//is running?
public abstract class Crawler
        extends AbstractResumableJob {

    private static final Logger LOG =
            LoggerFactory.getLogger(Crawler.class);

    private static final int DOUBLE_PROGRESS_SCALE = 4;
    private static final int DOUBLE_PERCENT_SCALE = -2;
    private static final int MINIMUM_DELAY = 1;
    private static final long STATUS_LOGGING_INTERVAL =
            TimeUnit.SECONDS.toMillis(5);
    private static final InheritableThreadLocal<Crawler> INSTANCE =
            new InheritableThreadLocal<>();

    private final CrawlerConfig config;
    private final Collector collector;
    private Importer importer;

    private Path workDir;
    private Path tempDir;
    private Path downloadDir;

    private boolean stopped;
    // This processedCount does not take into account alternate references such
    // as redirects. It is a cleaner representation for end-users and speed
    // things a bit bit not having to obtain that value from the database at
    // every progress change.,
    private long processedCount;
    private long lastStatusLoggingTime;

    private IDataStoreEngine dataStoreEngine;
    private CrawlDocInfoService crawlDocInfoService;

    /**
     * Constructor.
     * @param config crawler configuration
     * @param collector the collector this crawler is attached to
     */
    public Crawler(CrawlerConfig config, Collector collector) {
        Objects.requireNonNull(config, "'config' must not be null");
        Objects.requireNonNull(config, "'collector' must not be null");
        this.config = config;
        this.collector = collector;
        INSTANCE.set(this);
    }

    public static Crawler get() {
        return INSTANCE.get();
    }

    /**
     * Gets the event manager.
     * @return event manager
     * @since 2.0.0
     */
    public EventManager getEventManager() {
        return collector.getEventManager();
    }

    @Override
    public String getId() {
        return config.getId();
    }

    /**
     * Whether the crawler job was stopped.
     * @return <code>true</code> if stopped
     */
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void stop(JobStatus jobStatus, JobSuite suite) {
        getEventManager().fire(CrawlerEvent.create(CRAWLER_STOP_BEGIN, this));
        stopped = true;
        LOG.info("Stopping the crawler.");
    }

    /**
     * Gets the crawler Importer module.
     * @return the Importer
     */
    public Importer getImporter() {
        return importer;
    }

    public CachedStreamFactory getStreamFactory() {
        return collector.getStreamFactory();
    }

    /**
     * Gets the crawler configuration.
     * @return the crawler configuration
     */
    public CrawlerConfig getCrawlerConfig() {
        return config;
    }

    // really make public? Or have a getCollectorId() method instead?
    public Collector getCollector() {
        return collector;
    }

    public Path getWorkDir() {
        if (workDir != null) {
            return workDir;
        }

        String fileSafeId = FileUtil.toSafeFileName(getId());
        Path dir = collector.getWorkDir().resolve(fileSafeId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CollectorException(
                    "Could not create crawler working directory.", e);
        }
        workDir = dir;
        return workDir;
    }
    public Path getTempDir() {
        if (tempDir != null) {
            return tempDir;
        }

        String fileSafeId = FileUtil.toSafeFileName(getId());
        Path dir = collector.getTempDir().resolve(fileSafeId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CollectorException(
                    "Could not create crawler temp directory.", e);
        }
        tempDir = dir;
        return tempDir;
    }


    public Path getDownloadDir() {
        return downloadDir;
    }

    @Override
    protected void startExecution(
            JobStatusUpdater statusUpdater, JobSuite suite) {

        boolean resume = initCrawler();

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        importer = new Importer(
                getCrawlerConfig().getImporterConfig(),
                getEventManager());
        processedCount = crawlDocInfoService.getProcessedCount();

        if (Boolean.getBoolean("enableJMX")) {
            registerMonitoringMbean();
        }

        try {
            getEventManager().fire(CrawlerEvent.create(CRAWLER_RUN_BEGIN, this));
            //TODO rename "beforeExecution and afterExecution"?
            prepareExecution(statusUpdater, suite, resume);

            //TODO move this code to a config validator class?
            if (StringUtils.isBlank(getCrawlerConfig().getId())) {
                throw new CollectorException("Crawler must be given "
                        + "a unique identifier (id).");
            }

            lastStatusLoggingTime = System.currentTimeMillis();
            doExecute(statusUpdater, suite);
        } finally {
            try {
                stopWatch.stop();
                if (LOG.isInfoEnabled()) {
                    LOG.info("Crawler executed in {}.",
                            DurationFormatter.FULL.withLocale(
                                   Locale.ENGLISH).format(stopWatch.getTime()));
                }
                cleanupExecution(statusUpdater, suite/*, crawlDataStore*/);
            } finally {
                destroyCrawler();
            }
        }

    }

    protected boolean initCrawler() {
        getEventManager().fire(CrawlerEvent.create(CRAWLER_INIT_BEGIN, this));

        //--- Ensure good state/config ---
        if (StringUtils.isBlank(config.getId())) {
            throw new CollectorException("Crawler must be given "
                    + "a unique identifier (id).");
        }

        //--- Directories ---
        this.downloadDir = getWorkDir().resolve("downloads");
        this.dataStoreEngine = config.getDataStoreEngine();
        this.dataStoreEngine.init(this);
        this.crawlDocInfoService = new CrawlDocInfoService(
                getId(), dataStoreEngine, getCrawlReferenceType());

        boolean resuming = crawlDocInfoService.open();
        getEventManager().fire(CrawlerEvent.create(CRAWLER_INIT_END, this));
        return resuming;
    }

    protected Class<? extends CrawlDocInfo> getCrawlReferenceType() {
        return CrawlDocInfo.class;
    }

    public IDataStoreEngine getDataStoreEngine() {
        return dataStoreEngine;
    }

    public CrawlDocInfoService getCrawlReferenceService() {
        return crawlDocInfoService;
    }

    public void clean() {
        initCrawler();
        getEventManager().fire(CrawlerEvent.create(CRAWLER_CLEAN_BEGIN, this));
        destroyCrawler();
        try {
            FileUtils.deleteDirectory(getTempDir().toFile());
            FileUtils.deleteDirectory(getWorkDir().toFile());
            getEventManager().fire(CrawlerEvent.create(CRAWLER_CLEAN_END, this));
        } catch (IOException e) {
            throw new CollectorException("Could clean crawler directory.");
        }
    }


    public void importDataStore(Path inFile) {
        initCrawler();
        try {
            DataStoreImporter.importDataStore(this, inFile);
        } catch (IOException e) {
            throw new CollectorException("Could not import data store.", e);
        } finally {
            destroyCrawler();
        }

    }
    public Path exportDataStore(Path dir) {
        initCrawler();
        try {
            return DataStoreExporter.exportDataStore(this, dir);
        } catch (IOException e) {
            throw new CollectorException("Could not export data store.", e);
        } finally {
            destroyCrawler();
        }
    }

    protected void destroyCrawler() {
        crawlDocInfoService.close();
        dataStoreEngine.close();

        //TODO shall we clear crawler listeners, or leave to collector
        // to clean all?
        // eventManager.clearListeners();
    }

    protected abstract void prepareExecution(
            JobStatusUpdater statusUpdater, JobSuite suite, boolean resume);

    protected abstract void cleanupExecution(
            JobStatusUpdater statusUpdater, JobSuite suite);


    protected void doExecute(JobStatusUpdater statusUpdater, JobSuite suite) {

        //--- Process start/queued references ----------------------------------
        LOG.info("Crawling references...");
        ImporterPipelineContext contextPrototype =
                new ImporterPipelineContext(this);
        processReferences(statusUpdater, suite, contextPrototype);

        if (!isStopped()) {
            handleOrphans(statusUpdater, suite);
        }

        ICommitter committer = getCrawlerConfig().getCommitter();
        if (committer != null) {
            LOG.info("Crawler {}: committing documents.",
                    (isStopped() ? "stopping" : "finishing"));
            committer.commit();
        }

        LOG.info("{} reference(s) processed.", processedCount);

        LOG.debug("Removing empty directories");
        FileUtil.deleteEmptyDirs(getDownloadDir().toFile());

        if (!isStopped()) {
            getEventManager().fire(CrawlerEvent.create(CRAWLER_RUN_END, this));
        } else {
            getEventManager().fire(CrawlerEvent.create(CRAWLER_STOP_END, this));
        }
        LOG.info("Crawler {}", (isStopped() ? "stopped." : "completed."));
    }

    protected void handleOrphans(
            JobStatusUpdater statusUpdater, JobSuite suite) {

        OrphansStrategy strategy = config.getOrphansStrategy();
        if (strategy == null) {
            // null is same as ignore
            strategy = OrphansStrategy.IGNORE;
        }

        // If PROCESS, we do not care to validate if really orphan since
        // all cache items will be reprocessed regardless
        if (strategy == OrphansStrategy.PROCESS) {
            reprocessCacheOrphans(statusUpdater, suite);
            return;
        }

        if (strategy == OrphansStrategy.DELETE) {
            deleteCacheOrphans(statusUpdater, suite);
        }
        // else, ignore (i.e. don't do anything)
        //TODO log how many where ignored (cache count)
    }

    protected boolean isMaxDocuments() {
        return getCrawlerConfig().getMaxDocuments() > -1
                && processedCount >= getCrawlerConfig().getMaxDocuments();
    }

    protected void reprocessCacheOrphans(
            JobStatusUpdater statusUpdater, JobSuite suite) {
        if (isMaxDocuments()) {
            LOG.info("Max documents reached. "
                    + "Not reprocessing orphans (if any).");
            return;
        }
        LOG.info("Reprocessing any cached/orphan references...");

        long count = 0;
        for (CrawlDocInfo ref : crawlDocInfoService.getCachedIterable()) {
            executeQueuePipeline(ref);
            count++;
        }
        if (count > 0) {
            ImporterPipelineContext contextPrototype =
                    new ImporterPipelineContext(this);
            contextPrototype.setOrphan(true);
            processReferences(statusUpdater, suite, contextPrototype);
        }
        LOG.info("Reprocessed {} cached/orphan references.", count);
    }

    protected abstract void executeQueuePipeline(CrawlDocInfo ref);

    protected void deleteCacheOrphans(
            JobStatusUpdater statusUpdater, JobSuite suite) {
        LOG.info("Deleting orphan references (if any)...");
        long count = 0;
        for (CrawlDocInfo ref : crawlDocInfoService.getCachedIterable()) {
            crawlDocInfoService.queue(ref);
            count++;
        }
        if (count > 0) {
            ImporterPipelineContext contextPrototype =
                    new ImporterPipelineContext(this);
            contextPrototype.setDelete(true);
            processReferences(statusUpdater, suite, contextPrototype);
        }
        LOG.info("Deleted {} orphan references.", count);
    }


    protected void processReferences(
            final JobStatusUpdater statusUpdater,
            final JobSuite suite,
            final ImporterPipelineContext contextPrototype) {


        int numThreads = getCrawlerConfig().getNumThreads();
        final CountDownLatch latch = new CountDownLatch(numThreads);
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i + 1;
            LOG.debug("Crawler thread #{} started.", threadIndex);
            pool.execute(new ProcessReferencesRunnable(
                    suite, statusUpdater, latch, contextPrototype));
        }

        try {
            latch.await();
            pool.shutdown();
        } catch (InterruptedException e) {
             Thread.currentThread().interrupt();
             throw new CollectorException(e);
        }
    }

    // return <code>true</code> if more references to process
    protected boolean processNextReference(
            final JobStatusUpdater statusUpdater,
            final ImporterPipelineContext context) {
        if (!context.isDelete() && isMaxDocuments()) {
            LOG.info("Maximum documents reached: {}",
                    getCrawlerConfig().getMaxDocuments());
            return false;
        }
        CrawlDocInfo queuedRef =
                crawlDocInfoService.nextQueued().orElse(null);
        context.setCrawlReference(queuedRef);

        LOG.trace("Processing next reference from Queue: {}", queuedRef);
        if (queuedRef != null) {
            StopWatch watch = null;
            if (LOG.isDebugEnabled()) {
                watch = new StopWatch();
                watch.start();
            }
            processNextQueuedCrawlData(context);
            setProgress(statusUpdater);
            if (LOG.isDebugEnabled()) {
                watch.stop();
                LOG.debug("{} to process: {}", watch, queuedRef.getReference());
            }
        } else {
            long activeCount = crawlDocInfoService.getActiveCount();
            boolean queueEmpty = crawlDocInfoService.isQueueEmpty();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Number of references currently being "
                        + "processed: {}", activeCount);
                LOG.trace("Is reference queue empty? {}", queueEmpty);
            }
            if (activeCount == 0 && queueEmpty) {
                return false;
            }
            Sleeper.sleepMillis(MINIMUM_DELAY);
        }
        return true;
    }

    private void registerMonitoringMbean() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            String objName = "com.norconex.collector.crawler:type=" +
                    getCrawlerConfig().getId();
            LOG.info("Adding MBean for JMX monitoring: {}", objName);
            ObjectName name = new ObjectName(objName);
            Monitoring mbean = new Monitoring(crawlDocInfoService);
            mbs.registerMBean(mbean, name);
        } catch (MalformedObjectNameException |
                 InstanceAlreadyExistsException |
                 MBeanRegistrationException |
                 NotCompliantMBeanException e) {
            throw new CollectorException(e);
        }
    }

    private void setProgress(JobStatusUpdater statusUpdater) {
        long queued = crawlDocInfoService.getQueuedCount();
        long processed = processedCount;
        long total = queued + processed;

        double progress = 0;

        if (total != 0) {
            progress = BigDecimal.valueOf(processed)
                    .divide(BigDecimal.valueOf(total),
                            DOUBLE_PROGRESS_SCALE, RoundingMode.DOWN)
                    .doubleValue();
        }
        statusUpdater.setProgress(progress);

        statusUpdater.setNote(
                NumberFormat.getIntegerInstance().format(processed)
                + " references processed out of "
                + NumberFormat.getIntegerInstance().format(total));

        if (LOG.isInfoEnabled()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStatusLoggingTime > STATUS_LOGGING_INTERVAL) {
                lastStatusLoggingTime = currentTime;
                int percent = BigDecimal.valueOf(progress).movePointLeft(
                        DOUBLE_PERCENT_SCALE).intValue();
                LOG.info("{}% completed ({} processed/{} total)",
                        percent, processed, total);
            }
        }
    }

    //TODO given latest changes in implementing methods, shall we only consider
    //using generics instead of having this wrapping method?
    protected abstract Doc wrapDocument(
            CrawlDocInfo crawlRef, Doc document);
    protected void initCrawlReference(
            CrawlDocInfo crawlRef,
            CrawlDocInfo cachedCrawlRef,
            Doc document) {
        // default does nothing
    }

    private void processNextQueuedCrawlData(ImporterPipelineContext context) {
        CrawlDocInfo crawlRef = context.getCrawlReference();
        String reference = crawlRef.getReference();
        Doc doc = wrapDocument(crawlRef, new Doc(
                crawlRef.getReference(), getStreamFactory().newInputStream()));
        context.setDocument(doc);

        CrawlDocInfo cachedCrawlRef =
                crawlDocInfoService.getCached(reference).orElse(null);
        context.setCachedCrawlReference(cachedCrawlRef);

        doc.getMetadata().set(
                CrawlDocMetadata.COLLECTOR_IS_CRAWL_NEW,
                cachedCrawlRef == null);

        initCrawlReference(crawlRef, cachedCrawlRef, doc);

        try {
            if (context.isDelete()) {
                deleteReference(crawlRef, doc);
                finalizeDocumentProcessing(
                        crawlRef, doc, cachedCrawlRef);
                return;
            }
            LOG.debug("Processing reference: {}", reference);

            ImporterResponse response = executeImporterPipeline(context);

            if (response != null) {
                processImportResponse(
                        response, crawlRef, cachedCrawlRef);
            } else {
                if (crawlRef.getState().isNewOrModified()) {
                    crawlRef.setState(CrawlState.REJECTED);
                }
                //TODO Fire an event here? If we get here, the importer did
                //not kick in,
                //so do not fire REJECTED_IMPORT (like it used to).
                //Errors should have fired
                //something already so do not fire two REJECTED... but
                //what if a previous issue did not fire a REJECTED_*?
                //This should not happen, but keep an eye on that.
                //OR do we want to always fire REJECTED_IMPORT on import failure
                //(in addition to whatever) and maybe a new REJECTED_COLLECTOR
                //when it did not reach the importer module?
                finalizeDocumentProcessing(crawlRef, doc, cachedCrawlRef);
            }
        } catch (Throwable e) {
            //TODO do we really want to catch anything other than
            // HTTPFetchException?  In case we want special treatment to the
            // class?
            crawlRef.setState(CrawlState.ERROR);
            getEventManager().fire(
                    CrawlerEvent.create(REJECTED_ERROR, this, crawlRef, e));
            if (LOG.isDebugEnabled()) {
                LOG.info("Could not process document: {} ({})",
                        reference, e.getMessage(), e);
            } else {
                LOG.info("Could not process document: {} ({})",
                        reference, e.getMessage());
            }
            finalizeDocumentProcessing(crawlRef, doc, cachedCrawlRef);

            // Rethrow exception is we want the crawler to stop
            List<Class<? extends Exception>> exceptionClasses =
                    config.getStopOnExceptions();
            if (CollectionUtils.isNotEmpty(exceptionClasses)) {
                for (Class<? extends Exception> c : exceptionClasses) {
                    if (c.isAssignableFrom(e.getClass())) {
                        throw e;
                    }
                }
            }
        }
    }

    private void processImportResponse(
            ImporterResponse response,
            CrawlDocInfo crawlRef,
            CrawlDocInfo cachedCrawlRef) {

        Doc doc = response.getDocument();
        if (response.isSuccess()) {
            getEventManager().fire(CrawlerEvent.create(
                    DOCUMENT_IMPORTED, this, crawlRef, response));
            Doc wrappedDoc = wrapDocument(crawlRef, doc);
            executeCommitterPipeline(this, wrappedDoc,
                    crawlRef, cachedCrawlRef);
        } else {
            crawlRef.setState(CrawlState.REJECTED);
            getEventManager().fire(CrawlerEvent.create(
                    REJECTED_IMPORT, this, crawlRef, response));
            LOG.debug("Importing unsuccessful for \"{}\": {}",
                    crawlRef.getReference(),
                    response.getImporterStatus().getDescription());
        }
        finalizeDocumentProcessing(crawlRef, doc, cachedCrawlRef);
        ImporterResponse[] children = response.getNestedResponses();
        for (ImporterResponse child : children) {
            CrawlDocInfo embeddedCrawlRef = createEmbeddedCrawlReference(
                    child.getReference(), crawlRef);
            CrawlDocInfo embeddedCachedCrawlRef =
                    crawlDocInfoService.getCached(
                            child.getReference()).orElse(null);
            processImportResponse(child,
                    embeddedCrawlRef, embeddedCachedCrawlRef);
        }
    }


    private void finalizeDocumentProcessing(CrawlDocInfo crawlRef,
            Doc doc, CrawlDocInfo cachedCrawlRef) {

        //--- Ensure we have a state -------------------------------------------
        if (crawlRef.getState() == null) {
            LOG.warn("Reference status is unknown for \"{}\". "
                    + "This should not happen. Assuming bad status.",
                    crawlRef.getReference());
            crawlRef.setState(CrawlState.BAD_STATUS);
        }

        try {

            // important to call this before copying properties further down
            beforeFinalizeDocumentProcessing(crawlRef, doc, cachedCrawlRef);

            //--- If doc crawl was incomplete, set missing info from cache -----
            // If document is not new or modified, it did not go through
            // the entire crawl life cycle for a document so maybe not all info
            // could be gathered for a reference.  Since we do not want to lose
            // previous information when the crawl was effective/good
            // we copy it all that is non-null from cache.
            if (!crawlRef.getState().isNewOrModified() && cachedCrawlRef != null) {
                //TODO maybe new CrawlData instances should be initialized with
                // some of cache data available instead?
                BeanUtil.copyPropertiesOverNulls(crawlRef, cachedCrawlRef);
            }

            //--- Deal with bad states (if not already deleted) ----------------
            if (!crawlRef.getState().isGoodState()
                    && !crawlRef.getState().isOneOf(CrawlState.DELETED)) {

                //TODO If duplicate, consider it as spoiled if a cache version
                // exists in a good state.
                // This involves elaborating the concept of duplicate
                // or "reference change" in this core project. Otherwise there
                // is the slim possibility right now that a Collector
                // implementation marking references as duplicate may
                // generate orphans (which may be caught later based
                // on how orphans are handled, but they should not be ever
                // considered orphans in the first place).
                // This could remove the need for the
                // markReferenceVariationsAsProcessed(...) method

                SpoiledReferenceStrategy strategy =
                        getSpoiledStateStrategy(crawlRef);

                if (strategy == SpoiledReferenceStrategy.IGNORE) {
                    LOG.debug("Ignoring spoiled reference: {}",
                            crawlRef.getReference());
                } else if (strategy == SpoiledReferenceStrategy.DELETE) {
                    // Delete if previous state exists and is not already
                    // marked as deleted.
                    if (cachedCrawlRef != null
                            && !cachedCrawlRef.getState().isOneOf(CrawlState.DELETED)) {
                        deleteReference(crawlRef, doc);
                    }
                } else {
                    // GRACE_ONCE:
                    // Delete if previous state exists and is a bad state,
                    // but not already marked as deleted.
                    if (cachedCrawlRef == null) {
                        // If graced once and has no cache, it means it
                        // was likely marked as invalid (dropped from cache
                        // by some store implementations).
                        // Then we assume it is ready to be deleted at the low
                        // risk of sending a deletion request previously sent
                        // (no harm).  This is to fix
                        // https://github.com/Norconex/collector-http/issues/635
                        //TODO handle better (make sure "processedInvalid"
                        // is no longer wiped by datastore on startup).
                        deleteReference(crawlRef, doc);
                    } else if (!cachedCrawlRef.getState().isOneOf(CrawlState.DELETED)) {
                        if (!cachedCrawlRef.getState().isGoodState()) {
                            deleteReference(crawlRef, doc);
                        } else {
                            LOG.debug("This spoiled reference is "
                                    + "being graced once (will be deleted "
                                    + "next time if still spoiled): {}",
                                    crawlRef.getReference());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Could not finalize processing of: {} ({})",
                    crawlRef.getReference(), e.getMessage(), e);
        }

        //--- Mark reference as Processed --------------------------------------
        try {
            processedCount++;
            crawlDocInfoService.processed(crawlRef);
            markReferenceVariationsAsProcessed(crawlRef);
        } catch (Exception e) {
            LOG.error("Could not mark reference as processed: {} ({})",
                    crawlRef.getReference(), e.getMessage(), e);
        }

        try {
            if (doc != null) {
                doc.getInputStream().dispose();
            }
        } catch (Exception e) {
            LOG.error("Could not dispose of resources.", e);
        }
    }

    /**
     * Gives implementors a change to take action on a document before
     * its processing is being finalized (cycle end-of-life for a crawled
     * reference). Default implementation does nothing.
     * @param crawlRef crawl data with data the crawler was able to obtain,
     *                  guaranteed to have a non-null state
     * @param doc the document
     * @param cachedCrawlRef cached crawl data
     *        (<code>null</code> if document was not crawled before)
     */
    protected void beforeFinalizeDocumentProcessing(CrawlDocInfo crawlRef,
            Doc doc, CrawlDocInfo cachedCrawlRef) {
        //NOOP
    }

    protected abstract void markReferenceVariationsAsProcessed(
            CrawlDocInfo crawlRef);


    protected abstract CrawlDocInfo createEmbeddedCrawlReference(
            String embeddedReference, CrawlDocInfo parentCrawlRef);

    protected abstract ImporterResponse executeImporterPipeline(
            ImporterPipelineContext context);

    //TODO, replace with DocumentPipelineContext?
    protected abstract void executeCommitterPipeline(
            Crawler crawler,
            Doc doc,
            CrawlDocInfo crawlRef,
            CrawlDocInfo cachedCrawlRef);

    private Properties getNullSafeMetadata(Doc doc) {
        if (doc == null) {
            return new Properties();
        }
        return doc.getMetadata();
    }

    private SpoiledReferenceStrategy getSpoiledStateStrategy(
            CrawlDocInfo crawlData) {
        ISpoiledReferenceStrategizer strategyResolver =
                config.getSpoiledReferenceStrategizer();
        SpoiledReferenceStrategy strategy =
                strategyResolver.resolveSpoiledReferenceStrategy(
                        crawlData.getReference(), crawlData.getState());
        if (strategy == null) {
            // Assume the generic default (DELETE)
            strategy =  GenericSpoiledReferenceStrategizer
                    .DEFAULT_FALLBACK_STRATEGY;
        }
        return strategy;
    }

    private void deleteReference(
            CrawlDocInfo crawlRef, Doc doc) {
        LOG.debug("Deleting reference: {}", crawlRef.getReference());
        ICommitter committer = getCrawlerConfig().getCommitter();
        crawlRef.setState(CrawlState.DELETED);
        if (committer != null) {
            committer.remove(
                    crawlRef.getReference(), getNullSafeMetadata(doc));
        }
        getEventManager().fire(CrawlerEvent.create(
                DOCUMENT_COMMITTED_REMOVE, this, crawlRef, doc));
    }

    private final class ProcessReferencesRunnable implements Runnable {
        private final ImporterPipelineContext importerContextPrototype;
        private final JobSuite suite;
        private final JobStatusUpdater statusUpdater;
        private final CountDownLatch latch;

        private ProcessReferencesRunnable(
                JobSuite suite,
                JobStatusUpdater statusUpdater,
                CountDownLatch latch,
                ImporterPipelineContext importerContextPrototype) {
            this.suite = suite;
            this.statusUpdater = statusUpdater;
            this.latch = latch;
            this.importerContextPrototype = importerContextPrototype;
        }

        @Override
        public void run() {
            JobSuite.setCurrentJobId(statusUpdater.getJobId());
            try {
                while (!isStopped()) {
                    try {
                        if (!processNextReference(statusUpdater,
                                new ImporterPipelineContext(
                                        importerContextPrototype))) {
                            break;
                        }
                    } catch (Exception e) {
                        LOG.error(
                              "An error occured that could compromise "
                            + "the stability of the crawler. Stopping "
                            + "excution to avoid further issues...", e);
                        stop(suite.getJobStatus(suite.getRootJob()), suite);
                    }
                }
            } catch (Exception e) {
                LOG.error("Problem in thread execution.", e);
            } finally {
                latch.countDown();
            }
        }
    }

    @Override
    public String toString() {
        return getId();
    }
}
