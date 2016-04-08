/* Copyright 2014-2015 Norconex Inc.
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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.Locale;
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

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.crawler.ICrawlerConfig.OrphansStrategy;
import com.norconex.collector.core.crawler.event.CrawlerEvent;
import com.norconex.collector.core.crawler.event.CrawlerEventManager;
import com.norconex.collector.core.data.BaseCrawlData;
import com.norconex.collector.core.data.CrawlState;
import com.norconex.collector.core.data.ICrawlData;
import com.norconex.collector.core.data.store.ICrawlDataStore;
import com.norconex.collector.core.doc.CollectorMetadata;
import com.norconex.collector.core.jmx.Monitoring;
import com.norconex.collector.core.spoil.ISpoiledReferenceStrategizer;
import com.norconex.collector.core.spoil.SpoiledReferenceStrategy;
import com.norconex.collector.core.spoil.impl.GenericSpoiledReferenceStrategizer;
import com.norconex.committer.core.ICommitter;
import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.time.DurationUtil;
import com.norconex.importer.Importer;
import com.norconex.importer.doc.ImporterDocument;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.response.ImporterResponse;
import com.norconex.jef4.job.AbstractResumableJob;
import com.norconex.jef4.status.IJobStatus;
import com.norconex.jef4.status.JobStatusUpdater;
import com.norconex.jef4.suite.JobSuite;

/**
 * Abstract crawler implementation providing a common base to building
 * crawlers.
 * @author Pascal Essiembre
 */
public abstract class AbstractCrawler 
        extends AbstractResumableJob implements ICrawler {

    private static final Logger LOG = 
            LogManager.getLogger(AbstractCrawler.class);

    private static final int DOUBLE_PROGRESS_SCALE = 4;
    private static final int DOUBLE_PERCENT_SCALE = -2;
    private static final int MINIMUM_DELAY = 1;
    private static final long STATUS_LOGGING_INTERVAL = 
            TimeUnit.SECONDS.toMillis(5);
    
    private final ICrawlerConfig config;
    private CrawlerEventManager crawlerEventManager;
    private Importer importer;
    private CachedStreamFactory streamFactory;
    
    private boolean stopped;
    // This processedCount does not take into account alternate references such
    // as redirects. It is a cleaner representation for end-users and speed 
    // things a bit bit not having to obtain that value from the database at 
    // every progress change.,
    private int processedCount;
    private long lastStatusLoggingTime;
    
    /**
     * Constructor.
     * @param config crawler configuration
     */
    public AbstractCrawler(ICrawlerConfig config) {
        this.config = config;
        try {
            FileUtils.forceMkdir(config.getWorkDir());
        } catch (IOException e) {
            throw new CollectorException("Cannot create working directory: "
                    + config.getWorkDir(), e);
        }
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
    public void stop(IJobStatus jobStatus, JobSuite suite) {
        stopped = true;
        LOG.info(getId() + ": Stopping the crawler.");
    }
    
    public Importer getImporter() {
        return importer;
    }
    
    public CachedStreamFactory getStreamFactory() {
        return streamFactory;
    }
    
    /**
     * Gets the crawler configuration
     * @return the crawler configuration
     */
    @Override
    public ICrawlerConfig getCrawlerConfig() {
        return config;
    }

    public void fireCrawlerEvent(
            String eventType, ICrawlData crawlData, Object subject) {
        crawlerEventManager.fireCrawlerEvent(
                new CrawlerEvent(eventType, crawlData, subject));
    }

    public File getBaseDownloadDir() {
        return new File(getCrawlerConfig().getWorkDir().getAbsolutePath() 
                + "/downloads");
    }
    public File getCrawlerDownloadDir() {
        return new File(getBaseDownloadDir() 
                + "/" + getCrawlerConfig().getId());
    }
    
    @Override
    public CrawlerEventManager getCrawlerEventManager() {
        return crawlerEventManager;
    }
    
    @Override
    protected void startExecution(
            JobStatusUpdater statusUpdater, JobSuite suite) {
        doExecute(statusUpdater, suite, false);
    }

    @Override
    protected void resumeExecution(
            JobStatusUpdater statusUpdater, JobSuite suite) {
        doExecute(statusUpdater, suite, true);
    }

    private void doExecute(JobStatusUpdater statusUpdater,
            JobSuite suite, boolean resume) {
        StopWatch stopWatch = new StopWatch();;
        stopWatch.start();
        ICrawlDataStore crawlDataStore = 
                config.getCrawlDataStoreFactory().createCrawlDataStore(
                        config, resume);

//        boolean incremental = !crawlDataStore.isCacheEmpty();
//System.out.println("IS Incremental? " + incremental);     
//xx
        
        
        this.crawlerEventManager = new CrawlerEventManager(
                this, getCrawlerConfig().getCrawlerListeners());
        importer = new Importer(getCrawlerConfig().getImporterConfig());
        streamFactory = importer.getStreamFactory();
        processedCount = crawlDataStore.getProcessedCount();
        registerMonitoringMbean(crawlDataStore);
        
        try {
            prepareExecution(statusUpdater, suite, crawlDataStore, resume);
            //TODO move this code to a config validator class?
            if (StringUtils.isBlank(getCrawlerConfig().getId())) {
                throw new CollectorException("Crawler must be given "
                        + "a unique identifier (id).");
            }
            if (resume) {
                fireCrawlerEvent(CrawlerEvent.CRAWLER_RESUMED, null, this);
            } else {
                fireCrawlerEvent(CrawlerEvent.CRAWLER_STARTED, null, this);
            }
            lastStatusLoggingTime = System.currentTimeMillis();
            execute(statusUpdater, suite, crawlDataStore);
        } finally {
            stopWatch.stop();
            LOG.info(getId() + ": Crawler executed in "
                    + DurationUtil.formatLong(
                            Locale.ENGLISH, stopWatch.getTime()) + ".");
            try {
                cleanupExecution(statusUpdater, suite, crawlDataStore);
            } finally {
                crawlDataStore.close();
            }
        }
    }
    
    protected abstract void prepareExecution(
            JobStatusUpdater statusUpdater, JobSuite suite, 
            ICrawlDataStore refStore, boolean resume);

    protected abstract void cleanupExecution(
            JobStatusUpdater statusUpdater, JobSuite suite, 
            ICrawlDataStore refStore);

    
    protected void execute(JobStatusUpdater statusUpdater,
            JobSuite suite, ICrawlDataStore refStore) {

        //--- Process start/queued references ----------------------------------
        LOG.info(getId() + ": Crawling references...");
        processReferences(refStore, statusUpdater, suite, false);

        if (!isStopped()) {
            handleOrphans(refStore, statusUpdater, suite);
        }
        
        ICommitter committer = getCrawlerConfig().getCommitter();
        if (committer != null) {
            LOG.info(getId() + ": Crawler " 
                    + (isStopped() ? "stopping" : "finishing")
                    + ": committing documents.");
            committer.commit();
        }

        LOG.info(getId() + ": " + processedCount + " reference(s) processed.");

        LOG.debug(getId() + ": Removing empty directories");
        FileUtil.deleteEmptyDirs(getCrawlerDownloadDir());

        if (!isStopped()) {
            fireCrawlerEvent(CrawlerEvent.CRAWLER_FINISHED, null, this);
        }
        LOG.info(getId() + ": Crawler "
                + (isStopped() ? "stopped." : "completed."));
    }
    
    protected void handleOrphans(ICrawlDataStore refStore,
            JobStatusUpdater statusUpdater, JobSuite suite) {
        OrphansStrategy strategy = config.getOrphansStrategy();
        if (strategy == null) {
            // null is same as ignore, so we end here
            return;
        }
        
        if (strategy == OrphansStrategy.DELETE) {
            LOG.info(getId() + ": Deleting orphan references (if any)...");
            deleteCacheOrphans(refStore, statusUpdater, suite);
        } else if (strategy == OrphansStrategy.PROCESS) {
            if (!isMaxDocuments()) {
                LOG.info(getId() 
                        + ": Re-processing orphan references (if any)...");
                reprocessCacheOrphans(refStore, statusUpdater, suite);
            } else {
                LOG.info(getId() 
                        + ": Max documents reached. Not reprocessing orphans "
                        + "(if any).");
            }
        }
        // else, ignore (i.e. don't do anything)
    }
    
    protected boolean isMaxDocuments() {
        return getCrawlerConfig().getMaxDocuments() > -1 
                && processedCount >= getCrawlerConfig().getMaxDocuments();
    }
    
    protected void reprocessCacheOrphans(
            ICrawlDataStore crawlDataStore, 
            JobStatusUpdater statusUpdater, JobSuite suite) {
        long count = 0;
        Iterator<ICrawlData> it = crawlDataStore.getCacheIterator();
        if (it != null) {
            while (it.hasNext()) {
                ICrawlData crawlData = it.next();
                executeQueuePipeline(crawlData, crawlDataStore);
                count++;
            }
            processReferences(crawlDataStore, statusUpdater, suite, false);
        }
        LOG.info(getId() + ": Reprocessed " + count + " orphan references...");
    }
    
    protected abstract void executeQueuePipeline(
            ICrawlData crawlData, ICrawlDataStore crawlDataStore);
    
    protected void deleteCacheOrphans(ICrawlDataStore crawlDataStore, 
            JobStatusUpdater statusUpdater, JobSuite suite) {
        long count = 0;
        Iterator<ICrawlData> it = crawlDataStore.getCacheIterator();
        if (it != null && it.hasNext()) {
            while (it.hasNext()) {
                crawlDataStore.queue(it.next());
                count++;
            }
            processReferences(crawlDataStore, statusUpdater, suite, true);
        }
        LOG.info(getId() + ": Deleted " + count + " orphan references...");
    }
    
    
    protected void processReferences(
            final ICrawlDataStore refStore,
            final JobStatusUpdater statusUpdater, 
            final JobSuite suite,
            final boolean delete) {
        
        int numThreads = getCrawlerConfig().getNumThreads();
        final CountDownLatch latch = new CountDownLatch(numThreads);
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i + 1;
            LOG.debug(getId() 
                    + ": Crawler thread #" + threadIndex + " started.");
            pool.execute(new ProcessReferencesRunnable(
                    suite, statusUpdater, refStore, delete, latch));
        }

        try {
            latch.await();
            pool.shutdown();
        } catch (InterruptedException e) {
             throw new CollectorException(e);
        }
    }
 
    // return <code>true</code> if more references to process
    protected boolean processNextReference(
            final ICrawlDataStore crawlDataStore,
            final JobStatusUpdater statusUpdater, 
            final boolean delete) {
        if (!delete && isMaxDocuments()) {
            LOG.info(getId() + ": Maximum documents reached: " 
                    + getCrawlerConfig().getMaxDocuments());
            return false;
        }
        BaseCrawlData queuedCrawlData = 
                (BaseCrawlData) crawlDataStore.nextQueued();
        
        if (LOG.isTraceEnabled()) {
            LOG.trace(getId() + " Processing next reference from Queue: " 
                    + queuedCrawlData);
        }
        if (queuedCrawlData != null) {
            StopWatch watch = null;
            if (LOG.isDebugEnabled()) {
                watch = new StopWatch();
                watch.start();
            }
            processNextQueuedCrawlData(queuedCrawlData, crawlDataStore, delete);
            setProgress(statusUpdater, crawlDataStore);
            if (LOG.isDebugEnabled()) {
                watch.stop();
                LOG.debug(getId() + ": " + watch.toString() 
                        + " to process: " + queuedCrawlData.getReference());
            }
        } else {
            int activeCount = crawlDataStore.getActiveCount();
            boolean queueEmpty = crawlDataStore.isQueueEmpty();
            if (LOG.isTraceEnabled()) {
                LOG.trace(getId() 
                        + " Number of references currently being processed: "
                        + activeCount);
                LOG.trace(getId() + " Is reference queue empty? " + queueEmpty);
            }
            if (activeCount == 0 && queueEmpty) {
                return false;
            }
            Sleeper.sleepMillis(MINIMUM_DELAY);
        }
        return true;
    }
    
    private void registerMonitoringMbean(ICrawlDataStore crawlDataStore) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer(); 
            ObjectName name = 
                    new ObjectName("com.norconex.collector.http.crawler:type=" + 
                            getCrawlerConfig().getId()); 
            Monitoring mbean = new Monitoring(crawlDataStore); 
            mbs.registerMBean(mbean, name);
        } catch (MalformedObjectNameException | 
                 InstanceAlreadyExistsException | 
                 MBeanRegistrationException | 
                 NotCompliantMBeanException e) {
            throw new CollectorException(e);
        }
    }
    
    private void setProgress(
            JobStatusUpdater statusUpdater, ICrawlDataStore db) {
        int queued = db.getQueueSize();
        int processed = processedCount;
        int total = queued + processed;
        
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
                LOG.info(getId() + ": " + percent + "% completed (" 
                        + processed + " processed/" + total + " total)");
            }
        }
    }
    
    //TODO given latest changes in implementing methods, shall we only consider
    //using generics instead of having this wrapping method?
    protected abstract ImporterDocument wrapDocument(
            ICrawlData crawlData, ImporterDocument document);
    protected void applyCrawlData(
            ICrawlData crawlData, ImporterDocument document) {
        // default does nothing 
    }
    
    private void processNextQueuedCrawlData(BaseCrawlData crawlData, 
            ICrawlDataStore crawlDataStore, boolean delete) {
        String reference = crawlData.getReference();
        ImporterDocument doc = wrapDocument(crawlData, new ImporterDocument(
                crawlData.getReference(), getStreamFactory().newInputStream()));
        applyCrawlData(crawlData, doc);
        
        
        //TODO create a composite object that has crawler, crawlData,
        // cachedCrawlData, ... To reduce the number of arguments passed around.
        // It could potentially be a base class for pipeline contexts too.
        BaseCrawlData cachedCrawlData = 
                (BaseCrawlData) crawlDataStore.getCached(reference);
        doc.getMetadata().setBoolean(
                CollectorMetadata.COLLECTOR_IS_CRAWL_NEW, 
                cachedCrawlData == null);
        try {
            if (delete) {
                deleteReference(crawlData, doc);
                finalizeDocumentProcessing(
                        crawlData, crawlDataStore, doc, cachedCrawlData);
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug(getId() + ": Processing reference: " + reference);
            }
            
            
            ImporterResponse response = executeImporterPipeline(
                    this, doc, crawlDataStore, crawlData, cachedCrawlData);
            
            if (response != null) {
                processImportResponse(
                        response, crawlDataStore, crawlData, cachedCrawlData);
            } else {
                if (crawlData.getState().isNewOrModified()) {
                    crawlData.setState(CrawlState.REJECTED);
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
                finalizeDocumentProcessing(
                        crawlData, crawlDataStore, doc, cachedCrawlData);
            }
        } catch (Exception e) {
            //TODO do we really want to catch anything other than 
            // HTTPFetchException?  In case we want special treatment to the 
            // class?
            crawlData.setState(CrawlState.ERROR);
            fireCrawlerEvent(CrawlerEvent.REJECTED_ERROR, crawlData, e);
            LOG.error(getId() + ": Could not process document: " + reference
                    + " (" + e.getMessage() + ")", e);
            finalizeDocumentProcessing(
                    crawlData, crawlDataStore, doc, cachedCrawlData);
        }
    }

    private void processImportResponse(
            ImporterResponse response, 
            ICrawlDataStore crawlDataStore,
            BaseCrawlData crawlData,
            BaseCrawlData cachedCrawlData) {
        
        ImporterDocument doc = response.getDocument();
        if (response.isSuccess()) {
            fireCrawlerEvent(
                    CrawlerEvent.DOCUMENT_IMPORTED, crawlData, response);
            ImporterDocument wrappedDoc = wrapDocument(crawlData, doc);
            executeCommitterPipeline(this, wrappedDoc, 
                    crawlDataStore, crawlData, cachedCrawlData);
        } else {
            crawlData.setState(CrawlState.REJECTED);
            fireCrawlerEvent(
                    CrawlerEvent.REJECTED_IMPORT, crawlData, response);
            LOG.debug(getId() + ": Importing unsuccessful for \"" 
                    + crawlData.getReference() + "\": "
                    + response.getImporterStatus().getDescription());
        }
        finalizeDocumentProcessing(
                crawlData, crawlDataStore, doc, cachedCrawlData);
        ImporterResponse[] children = response.getNestedResponses();
        for (ImporterResponse child : children) {
            BaseCrawlData embeddedCrawlData = createEmbeddedCrawlData(
                    child.getReference(), crawlData);
            processImportResponse(
                    child, crawlDataStore, embeddedCrawlData, cachedCrawlData);
        }
    }
    
    private void finalizeDocumentProcessing(BaseCrawlData crawlData,
            ICrawlDataStore store, ImporterDocument doc,
            ICrawlData cached) {

        //--- Ensure we have a state -------------------------------------------
        if (crawlData.getState() == null) {
            LOG.warn(getId() + ": reference status is unknown for \"" 
                    + crawlData.getReference() + "\". This should not "
                    + "happen. Assuming bad status.");
            crawlData.setState(CrawlState.BAD_STATUS);
        }
        
        //--- If a good document not recrawled, set some info from cache -------
        if (crawlData.getState().isGoodState() && cached != null) {
            //TODO new CrawlData instances should be initialized with cache
            //data available.
            if (crawlData.getContentType() == null) {
                crawlData.setContentType(cached.getContentType());
            }
            if (crawlData.getCrawlDate() == null) {
                crawlData.setCrawlDate(cached.getCrawlDate());
            }
        }
        
        //--- Deal with bad states (if not already deleted) --------------------
        try {
            if (!crawlData.getState().isGoodState()
                    && !crawlData.getState().isOneOf(
                            CrawlState.DELETED)) {

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
                        getSpoiledStateStrategy(crawlData);

                if (strategy == SpoiledReferenceStrategy.IGNORE) {
                    LOG.debug(getId() + ": ignoring spoiled reference: "
                            + crawlData.getReference());
                } else if (strategy == SpoiledReferenceStrategy.DELETE) {
                    // Delete if previous state exists and is not already 
                    // marked as deleted.
                    if (cached != null 
                            && !cached.getState().isOneOf(CrawlState.DELETED)) {
                        deleteReference(crawlData, doc);
                    }
                } else {
                    // GRACE_ONCE:
                    // Delete if previous state exists and is a bad state, 
                    // but not already marked as deleted.
                    if (cached != null 
                            && !cached.getState().isOneOf(CrawlState.DELETED)) {
                        if (!cached.getState().isGoodState()) {
                            deleteReference(crawlData, doc);
                        } else {
                            LOG.debug(getId() + ": this spoiled reference is "
                                    + "being graced once (will be deleted "
                                    + "next time if still spoiled): "
                                    + crawlData.getReference());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error(getId() + ": Could not resolve reference bad status: "
                    + crawlData.getReference()
                    + " (" + e.getMessage() + ")", e);
        }
        
        //--- Mark reference as Processed --------------------------------------
        try {
            processedCount++;
            store.processed(crawlData);
            markReferenceVariationsAsProcessed(crawlData, store);
        } catch (Exception e) {
            LOG.error(getId() + ": Could not mark reference as processed: " 
                    + crawlData.getReference()
                    + " (" + e.getMessage() + ")", e);
        }

        try {
            if (doc != null) {
                doc.getContent().dispose();
            }
        } catch (Exception e) {
            LOG.error(getId() + ": Could not dispose of resources.", e);
        }
    }
    
    protected abstract void markReferenceVariationsAsProcessed(
            BaseCrawlData crawlData, ICrawlDataStore refStore);
    
    
    protected abstract BaseCrawlData createEmbeddedCrawlData(
            String embeddedReference, ICrawlData parentCrawlData);
    
    //TODO replace args by Core DocumentPipelineContext?
    protected abstract ImporterResponse executeImporterPipeline(
            ICrawler crawler,
            ImporterDocument doc,
            ICrawlDataStore crawlDataStore, 
            BaseCrawlData crawlData,
            BaseCrawlData cachedCrawlData);
    
    protected abstract void executeCommitterPipeline(
            ICrawler crawler,
            ImporterDocument doc,
            ICrawlDataStore crawlDataStore,
            BaseCrawlData crawlData,
            BaseCrawlData cachedCrawlData);

    private ImporterMetadata getNullSafeMetadata(ImporterDocument doc) {
        if (doc == null) {
            return new ImporterMetadata();
        }
        return doc.getMetadata();
    }
    
    private SpoiledReferenceStrategy getSpoiledStateStrategy(
            BaseCrawlData crawlData) {
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
            BaseCrawlData crawlData, ImporterDocument doc) {
        LOG.debug(getId() + ": Deleting reference: " 
                + crawlData.getReference());
        ICommitter committer = getCrawlerConfig().getCommitter();
        crawlData.setState(CrawlState.DELETED);
        if (committer != null) {
            committer.remove(
                    crawlData.getReference(), getNullSafeMetadata(doc));
        }
        fireCrawlerEvent(
                CrawlerEvent.DOCUMENT_COMMITTED_REMOVE, crawlData, doc);
    }
    
    private final class ProcessReferencesRunnable implements Runnable {
        private final JobSuite suite;
        private final JobStatusUpdater statusUpdater;
        private final ICrawlDataStore crawlStore;
        private final boolean delete;
        private final CountDownLatch latch;

        private ProcessReferencesRunnable(JobSuite suite, 
                JobStatusUpdater statusUpdater,
                ICrawlDataStore refStore, boolean delete,
                CountDownLatch latch) {
            this.suite = suite;
            this.statusUpdater = statusUpdater;
            this.crawlStore = refStore;
            this.delete = delete;
            this.latch = latch;
        }

        @Override
        public void run() {
            JobSuite.setCurrentJobId(statusUpdater.getJobId());
            try {
                while (!isStopped()) {
                    try {
                        if (!processNextReference(
                                crawlStore, statusUpdater, delete)) {
                            break;
                        }
                    } catch (Exception e) {
                        LOG.fatal(getId() + ": "
                            + "An error occured that could compromise "
                            + "the stability of the crawler. Stopping "
                            + "excution to avoid further issues...", e);
                        stop(suite.getJobStatus(suite.getRootJob()), suite);
                    }
                }
            } catch (Exception e) {
                LOG.error(getId() + ": Problem in thread execution.", e);
            } finally {
                latch.countDown();
            }
        }
    }
    
}
