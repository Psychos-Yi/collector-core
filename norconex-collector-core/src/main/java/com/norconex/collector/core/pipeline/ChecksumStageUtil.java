/**
 * 
 */
package com.norconex.collector.core.pipeline;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.core.crawler.event.CrawlerEvent;
import com.norconex.collector.core.data.BaseCrawlData;
import com.norconex.collector.core.data.CrawlState;

/**
 * @author Pascal Essiembre
 *
 */
public final class ChecksumStageUtil {

    private static final Logger LOG = 
            LogManager.getLogger(ChecksumStageUtil.class);
    
    private ChecksumStageUtil() {
        super();
    }

    
    public static boolean resolveMetaChecksum(
            String newChecksum, BasePipelineContext ctx, Object subject) {
        return resolveChecksum(true, newChecksum, ctx, subject);
    }
    public static boolean resolveDocumentChecksum(
            String newChecksum, BasePipelineContext ctx, Object subject) {
        return resolveChecksum(false, newChecksum, ctx, subject);
    }

    
    // return false if checksum is rejected/unmodified
    private static boolean resolveChecksum(boolean isMeta, String newChecksum, 
            BasePipelineContext ctx, Object subject) {
        BaseCrawlData crawlData = ctx.getCrawlData();
        
        // Set new checksum on crawlData + metadata
        String type;
        if (isMeta) {
            crawlData.setMetaChecksum(newChecksum);
            type = "metadata";
        } else {
            crawlData.setDocumentChecksum(newChecksum);
            type = "document";
        }
        
        // Get old checksum from cache
        BaseCrawlData cachedCrawlData = (BaseCrawlData) 
                ctx.getCrawlDataStore().getCached(crawlData.getReference());
        String oldChecksum = null;
        if (cachedCrawlData != null) {
            if (isMeta) {
                oldChecksum = cachedCrawlData.getMetaChecksum();
            } else {
                oldChecksum = cachedCrawlData.getContentChecksum();
            }
        } else {
            LOG.debug("ACCEPTED " + type + " checkum (new): Reference=" 
                    + crawlData.getReference());
            return true;
        }
        
        // Compare checksums
        if (StringUtils.isNotBlank(newChecksum) 
                && Objects.equals(newChecksum, oldChecksum)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("REJECTED " + type 
                        + " checkum (unmodified): Reference=" 
                        + crawlData.getReference());
            }
            crawlData.setState(CrawlState.UNMODIFIED);
            ctx.fireCrawlerEvent(CrawlerEvent.REJECTED_UNMODIFIED, 
                    ctx.getCrawlData(), subject);
            return false;
        }
        LOG.debug("ACCEPTED " + type + " checksum (modified): Reference=" 
                + crawlData.getReference());
        return true;
    }
    
}
