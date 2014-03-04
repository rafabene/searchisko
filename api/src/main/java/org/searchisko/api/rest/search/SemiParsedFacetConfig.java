/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest.search;

import java.util.Map;

/**
 * @author Lukas Vlcek
 */
public class SemiParsedFacetConfig {
	private String facetName;
	private String facetType;
	private String fieldName;
	private Map<String, Object> optionalSettings;
	private boolean filtered = false;
	private int filteredSize = 0;

	public void setFacetName(String value) {
		this.facetName = value;
	}

	public String getFacetName() {
		return this.facetName;
	}

	public void setFacetType(String value) {
		this.facetType = value;
	}

	public String getFacetType() {
		return this.facetType;
	}

	public void setFieldName(String value) {
		this.fieldName = value;
	}

	public String getFieldName() {
		return this.fieldName;
	}

	public void setOptionalSettings(Map<String, Object> object) {
		this.optionalSettings = object;
	}

	public Map<String, Object> getOptionalSettings() {
		return this.optionalSettings;
	}

	public void setFiltered(boolean value) {
		this.filtered = value;
	}

	public boolean isFiltered() {
		return this.filtered;
	}

	public void setFilteredSize(int value) {
		this.filteredSize = value;
	}

	public int getFilteredSize() {
		return this.filteredSize;
	}
}
