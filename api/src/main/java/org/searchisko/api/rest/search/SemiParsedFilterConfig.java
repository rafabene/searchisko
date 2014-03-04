/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest.search;

/**
 * @author Lukas Vlcek
 */
public abstract class SemiParsedFilterConfig {
	private String filterName;
	private String fieldName;

	public void setFilterName(String filterName) { this.filterName = filterName; }
	public String getFilterName() { return this.filterName; }

	public void setFieldName(String fieldName) { this.fieldName = fieldName; }
	public String getFieldName() { return this.fieldName; }
}
