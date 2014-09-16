/* Copyright 2014 Norconex Inc.
 * 
 * This file is part of Norconex Collector Core.
 * 
 * Norconex Collector Core is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Norconex Collector Core is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Norconex Collector Core. If not, 
 * see <http://www.gnu.org/licenses/>.
 */
package com.norconex.collector.core.doccrawl;

import java.lang.reflect.InvocationTargetException;

import org.apache.commons.beanutils.BeanUtils;

import com.norconex.collector.core.CollectorException;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;


/**
 * Create a new {@link IDocCrawl} with a default state of NEW.
 * @author Pascal Essiembre
 */
public class BasicDocCrawl implements IDocCrawl {

    private static final long serialVersionUID = 8711781555253202315L;

    private String reference;
    private String parentRootReference;
    private boolean isRootParentReference;
    private DocCrawlState state;
    private String metaChecksum;
    private String contentChecksum;
    
    /**
     * Constructor.
     */
    public BasicDocCrawl() {
        super();
        setState(DocCrawlState.NEW);
    }
    @Override
    public String getReference() {
        return reference;
    }
    public void setReference(String reference) {
        this.reference = reference;
    }

    @Override
    public String getParentRootReference() {
        return parentRootReference;
    }
    public void setParentRootReference(String parentRootReference) {
        this.parentRootReference = parentRootReference;
    }

    @Override
    public boolean isRootParentReference() {
        return isRootParentReference;
    }
    public void setRootParentReference(boolean isRootParentReference) {
        this.isRootParentReference = isRootParentReference;
    }

    @Override
    public DocCrawlState getState() {
        return state;
    }
    public void setState(DocCrawlState state) {
        this.state = state;
    }
    
    public String getMetaChecksum() {
        return metaChecksum;
    }
    public void setMetaChecksum(String metaChecksum) {
        this.metaChecksum = metaChecksum;
    }

    public String getContentChecksum() {
        return contentChecksum;
    }
    public void setContentChecksum(String contentChecksum) {
        this.contentChecksum = contentChecksum;
    }
    @Override
    public IDocCrawl safeClone() {
        try {
            return (IDocCrawl) BeanUtils.cloneBean(this);
        } catch (IllegalAccessException | InstantiationException
                | InvocationTargetException | NoSuchMethodException e) {
            throw new CollectorException(
                    "Cannot clone HttpDocReference: " + this, e);
        }
    }

    @Override
    public String toString() {
        return "BasicDocCrawlDetails [reference=" + reference
                + ", parentRootReference=" + parentRootReference
                + ", isRootParentReference=" + isRootParentReference
                + ", state=" + state + ", metaChecksum=" + metaChecksum
                + ", contentChecksum=" + contentChecksum + "]";
    }
    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof BasicDocCrawl)) {
            return false;
        }
        BasicDocCrawl castOther = (BasicDocCrawl) other;
        return new EqualsBuilder().append(reference, castOther.reference)
                .append(parentRootReference, castOther.parentRootReference)
                .append(isRootParentReference, castOther.isRootParentReference)
                .append(state, castOther.state)
                .append(metaChecksum, castOther.metaChecksum)
                .append(contentChecksum, castOther.contentChecksum).isEquals();
    }
    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(reference)
                .append(parentRootReference).append(isRootParentReference)
                .append(state).append(metaChecksum).append(contentChecksum)
                .toHashCode();
    }
}
