/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest.search;

import org.elasticsearch.common.settings.SettingsException;
import org.searchisko.api.service.ConfigService;

import java.util.Map;

/**
 * @author Lukas Vlcek
 */
public class ConfigParseUtil {

	/**
	 * Parse facet type.
	 *
	 * @param facetConfig
	 * @param facetName
	 * @return
	 */
	public static SemiParsedFacetConfig parseFacetType(final Object facetConfig, final String facetName) {
		try {
			Map<String, Object> map = (Map<String, Object>) facetConfig;
			if (map.isEmpty() || (map.size() > 1 && !map.containsKey("_filtered"))
					|| (map.size() > 2 && map.containsKey("_filtered"))) {
				throw new SettingsException("Incorrect configuration of fulltext search facet field '" + facetName
						+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS
						+ ": Multiple facet type is not allowed.");
			}
			SemiParsedFacetConfig config = new SemiParsedFacetConfig();
			config.setFacetName(facetName);
			for (String key : map.keySet()) {
				if ("_filtered".equals(key)) {
					Map<String, Object> filtered = (Map<String, Object>) map.get(key);
					config.setFilteredSize((Integer) filtered.get("size"));
					config.setFiltered(config.getFilteredSize() > 0 ? true : false);
				} else {
					config.setFacetType(key);
				}
			}
			// get map one level deeper
			map = (Map<String, Object>) map.get(config.getFacetType());
			if (!map.containsKey("field") || map.isEmpty()) {
				throw new SettingsException("Incorrect configuration of fulltext search facet field '" + facetName
						+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS
						+ ": Missing required [field] field.");
			}
			String fieldName = (String) map.get("field");
			if (fieldName == null || fieldName.isEmpty()) {
				throw new SettingsException("Incorrect configuration of fulltext search facet field '" + facetName
						+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS
						+ ": Invalid [field] field value.");
			}
			config.setFieldName(fieldName);
			config.setOptionalSettings(map);
			return config;
		} catch (ClassCastException e) {
			throw new SettingsException("Incorrect configuration of fulltext search facet field '" + facetName
					+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS + ".");
		}
	}
}
