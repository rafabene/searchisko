package org.searchisko.api.rest.search;

/**
 * Created by lukas on 14/03/14.
 */
public class SemiParsedFilterConfig {
	private String filterName;
	private String fieldName;

	public void setFilterName(String filterName) { this.filterName = filterName; }
	public String getFilterName() { return this.filterName; }

	public void setFieldName(String fieldName) { this.fieldName = fieldName; }
	public String getFieldName() { return this.fieldName; }
}
