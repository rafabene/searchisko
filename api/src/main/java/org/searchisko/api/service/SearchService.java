/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.service;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.Singleton;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.AndFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilteredQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryFilterBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.RangeFilterBuilder;
import org.elasticsearch.index.query.TermsFilterBuilder;
import org.elasticsearch.search.facet.datehistogram.DateHistogramFacetBuilder;
import org.elasticsearch.search.facet.terms.TermsFacetBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.searchisko.api.ContentObjectFields;
import org.searchisko.api.cache.IndexNamesCache;
import org.searchisko.api.model.QuerySettings;
import org.searchisko.api.model.QuerySettings.Filters;
import org.searchisko.api.model.SortByValue;
import org.searchisko.api.model.TimeoutConfiguration;
import org.searchisko.api.rest.search.ConfigParseUtil;
import org.searchisko.api.rest.search.SemiParsedFacetConfig;
import org.searchisko.api.rest.search.SemiParsedFilterConfig;

import static org.searchisko.api.rest.search.ConfigParseUtil.parseFacetType;

/**
 * Search business logic service.
 * 
 * @author Libor Krzyzanek
 * @author Vlastimil Elias (velias at redhat dot com)
 * @author Lukas Vlcek
 */
@Named
@ApplicationScoped
@Singleton
public class SearchService {

	@Inject
	protected SearchClientService searchClientService;

	@Inject
	protected StatsClientService statsClientService;

	@Inject
	protected ProviderService providerService;

	@Inject
	protected ConfigService configService;

	@Inject
	protected IndexNamesCache indexNamesCache;

	@Inject
	protected TimeoutConfiguration timeout;

	@Inject
	protected Logger log;

	/**
	 * Perform search operation.
	 * 
	 * @param querySettings to use for search
	 * @param responseUuid used for search response, we need it only to write it into statistics (so can be null)
	 * @return search response
	 */
	public SearchResponse performSearch(QuerySettings querySettings, String responseUuid, StatsRecordType statsRecordType) {
		try {
			SearchRequestBuilder srb = new SearchRequestBuilder(searchClientService.getClient());

			setSearchRequestIndicesAndTypes(querySettings, srb);

			QueryBuilder qb_fulltext = prepareQueryBuilder(querySettings);
			Map<String, FilterBuilder> searchFilters = handleCommonFiltersSettings(querySettings);
			srb.setQuery(applyCommonFilters(searchFilters, qb_fulltext));

			searchFilters.put("fulltext_query", new QueryFilterBuilder(qb_fulltext)); // ??
			handleFacetSettings(querySettings, searchFilters, srb);

			setSearchRequestSort(querySettings, srb);
			setSearchRequestHighlight(querySettings, srb);
			setSearchRequestFields(querySettings, srb);
			setSearchRequestFromSize(querySettings, srb);

			srb.setTimeout(TimeValue.timeValueSeconds(timeout.search()));

			log.log(Level.FINE, "ElasticSearch Search request: {0}", srb);

			final SearchResponse searchResponse = srb.execute().actionGet();

			statsClientService.writeStatisticsRecord(statsRecordType, responseUuid, searchResponse,
					System.currentTimeMillis(), querySettings);
			return searchResponse;
		} catch (ElasticSearchException e) {
			statsClientService.writeStatisticsRecord(statsRecordType, e, System.currentTimeMillis(), querySettings);
			throw e;
		}
	}

	/**
	 * Setup indices and types for the search request builder according to query settings.
	 *
	 * @param querySettings
	 * @param srb
	 */
	protected void setSearchRequestIndicesAndTypes(QuerySettings querySettings, SearchRequestBuilder srb) {

		List<String> contentTypes = null;
		if (querySettings.getFilters() != null) {
			contentTypes = querySettings.getFilters().valuesForField(ContentObjectFields.SYS_CONTENT_TYPE);
		}

		if (contentTypes != null && contentTypes.size() > 0) {
			List<String> allQueryIndices = new ArrayList<>();
			List<String> allQueryTypes = new ArrayList<>();
			for (String type : contentTypes) {
				Map<String, Object> typeDef = providerService.findContentType(type);
				if (typeDef == null) {
					throw new IllegalArgumentException("type");
				}
				String[] queryIndices = ProviderService.extractSearchIndices(typeDef, type);
				String queryType = ProviderService.extractIndexType(typeDef, type);
				if (log.isLoggable(Level.FINE)) {
					log.log(Level.FINE, "Query indices and types relevant to {0}: {1}", new Object[]{ContentObjectFields.SYS_CONTENT_TYPE, type});
					log.log(Level.FINE, "Query indices: {0}", Arrays.asList(queryIndices).toString());
					log.log(Level.FINE, "Query indices type: {0}", queryType);
				}
				Collections.addAll(allQueryIndices, queryIndices);
				allQueryTypes.add(queryType);
			}
			// array parameters can contain duplicities, but we assume Elasticsearch handles it correctly
			srb.setIndices((String[]) allQueryIndices.toArray());
			srb.setTypes((String[]) allQueryTypes.toArray());
		} else {
			List<String> sysTypesRequested = null;
			if (querySettings.getFilters() != null) {
				sysTypesRequested = querySettings.getFilters().valuesForField(ContentObjectFields.SYS_TYPE);
			}
			boolean isSysTypeFacet = (querySettings.getFacets() != null && querySettings.getFacets().contains(
					getFacetNameUsingSysTypeField()));

			// TODO: optimization - prepareIndexNamesCacheKey has no side-effects, consider using Memoize pattern (for example Guava's CacheBuilder)
			String indexNameCacheKey = prepareIndexNamesCacheKey(sysTypesRequested, isSysTypeFacet);
			Set<String> indexNames = indexNamesCache.get(indexNameCacheKey);
			if (indexNames == null) {
				indexNames = prepareIndexNames(sysTypesRequested, isSysTypeFacet);
				indexNamesCache.put(indexNameCacheKey, indexNames);
			}
			String[] queryIndices = indexNames.toArray(new String[indexNames.size()]);
			srb.setIndices(queryIndices);
			if (log.isLoggable(Level.FINE)) {
				log.log(Level.FINE, "Query indices: {0}", Arrays.asList(queryIndices).toString());
			}
		}
	}

	/**
	 * Prepare key for indexName cache.
	 * 
	 * @param sysTypesRequested to prepare key for
	 * @param isSysTypeFacet
	 * @return key value (never null)
	 */
	protected static String prepareIndexNamesCacheKey(List<String> sysTypesRequested, boolean isSysTypeFacet) {
		if (sysTypesRequested == null || sysTypesRequested.isEmpty())
			return "_all||" + isSysTypeFacet;

		if (sysTypesRequested.size() == 1) {
			return sysTypesRequested.get(0) + "||" + isSysTypeFacet;
		}

		TreeSet<String> ts = new TreeSet<String>(sysTypesRequested);
		StringBuilder sb = new StringBuilder();
		for (String k : ts) {
			sb.append(k).append("|");
		}
		sb.append("|").append(isSysTypeFacet);
		return sb.toString();
	}

	private Set<String> prepareIndexNames(List<String> sysTypesRequested, boolean isSysTypeFacet) {
		Set<String> indexNames = new LinkedHashSet<>();
		List<Map<String, Object>> allProviders = providerService.getAll();
		for (Map<String, Object> providerCfg : allProviders) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Map<String, Object>> types = (Map<String, Map<String, Object>>) providerCfg
						.get(ProviderService.TYPE);
				if (types != null) {
					for (String typeName : types.keySet()) {
						Map<String, Object> typeDef = types.get(typeName);
						if ((sysTypesRequested == null && !ProviderService.extractSearchAllExcluded(typeDef))
								|| (sysTypesRequested != null && ((isSysTypeFacet && !ProviderService
								.extractSearchAllExcluded(typeDef)) || sysTypesRequested.contains(ProviderService
								.extractSysType(typeDef, typeName))))) {
							indexNames.addAll(Arrays.asList(ProviderService.extractSearchIndices(typeDef, typeName)));
						}
					}
				}
			} catch (ClassCastException e) {
				throw new SettingsException("Incorrect configuration of 'type' section for sys_provider="
						+ providerCfg.get(ProviderService.NAME) + ". Contact administrators please.");
			}
		}
		return indexNames;
	}

	/**
	 * Prepare query builder based on query settings.
	 *
	 * Under the hood it creates either {@link org.elasticsearch.index.query.QueryStringQueryBuilder} using
	 * fields configured in {@link ConfigService#CFGNAME_SEARCH_FULLTEXT_QUERY_FIELDS} config file
	 * or {@link org.elasticsearch.index.query.MatchAllQueryBuilder} if query string is <code>null</code>.
	 *
	 * @param querySettings
	 * @return builder for query, never null
	 */
	protected QueryBuilder prepareQueryBuilder(QuerySettings querySettings) {
		if (querySettings.getQuery() != null) {
			QueryStringQueryBuilder qb = QueryBuilders.queryString(querySettings.getQuery());
			Map<String, Object> fields = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_QUERY_FIELDS);
			if (fields != null) {
				for (String fieldName : fields.keySet()) {
					String value = (String) fields.get(fieldName);
					if (value != null && !value.trim().isEmpty()) {
						try {
							qb.field(fieldName, Float.parseFloat(value));
						} catch (NumberFormatException e) {
							log.warning("Boost value has not valid float format for fulltext field " + fieldName
									+ " in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_QUERY_FIELDS);
							qb.field(fieldName);
						}
					} else {
						qb.field(fieldName);
					}
				}
			}
			return qb;
		} else {
			return QueryBuilders.matchAllQuery();
		}
	}

	/**
	 * @param querySettings
	 * @param srb
	 * @see <a href="http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/search-request-highlighting.html">Elasticsearch 0.90 - Highlighting</a>
	 */
	protected void setSearchRequestHighlight(QuerySettings querySettings, SearchRequestBuilder srb) {
		if (querySettings.getQuery() != null && querySettings.isQueryHighlight()) {
			Map<String, Object> hf = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_HIGHLIGHT_FIELDS);
			if (hf != null && !hf.isEmpty()) {
				srb.setHighlighterPreTags("<span class='hlt'>");
				srb.setHighlighterPostTags("</span>");
				srb.setHighlighterEncoder("html");
				for (String fieldName : hf.keySet()) {
					srb.addHighlightedField(fieldName, parseHighlightSettingIntParam(hf, fieldName, "fragment_size"),
							parseHighlightSettingIntParam(hf, fieldName, "number_of_fragments"),
							parseHighlightSettingIntParam(hf, fieldName, "fragment_offset"));
				}
			} else {
				throw new SettingsException("Fulltext search highlight requested but not configured by configuration document "
						+ ConfigService.CFGNAME_SEARCH_FULLTEXT_HIGHLIGHT_FIELDS + ". Contact administrators please.");
			}
		}
	}

	@SuppressWarnings("unchecked")
	protected int parseHighlightSettingIntParam(Map<String, Object> highlightConfigStructure, String fieldName,
			String paramName) {
		try {
			Map<String, Object> fieldConfig = (Map<String, Object>) highlightConfigStructure.get(fieldName);
			try {
				Object o = fieldConfig.get(paramName);
				if (o instanceof Integer) {
					return ((Integer) o).intValue();
				} else {
					return Integer.parseInt(o.toString());
				}
			} catch (Exception e) {
				throw new SettingsException("Missing or incorrect configuration of fulltext search highlight field '"
						+ fieldName + "' parameter '" + paramName + "' in configuration document "
						+ ConfigService.CFGNAME_SEARCH_FULLTEXT_HIGHLIGHT_FIELDS + ". Contact administrators please.");
			}
		} catch (ClassCastException e) {
			throw new SettingsException("Incorrect configuration of fulltext search highlight field '" + fieldName
					+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_HIGHLIGHT_FIELDS
					+ ". Contact administrators please.");
		}
	}

	private static final DateTimeFormatter DATE_TIME_FORMATTER_UTC = ISODateTimeFormat.dateTime().withZoneUTC();
	/**
	 * Maximal size of response.
	 */
	public static final int RESPONSE_MAX_SIZE = 500;

	/**
	 * @param querySettings
	 * @return filter builder if some filters are here, null if not filters necessary
	 */
	protected Map<String, FilterBuilder> handleCommonFiltersSettings(QuerySettings querySettings) {
		QuerySettings.Filters filters = querySettings.getFilters();
		Map<String, FilterBuilder> searchFilters = new LinkedHashMap<String, FilterBuilder>();

		if (filters != null) {

			Map<String, Object> filtersConfig = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_FILTER_FIELDS);
			for (String filterCandidateKey : filters.getFilterCandidatesKeys()) {
				if (filtersConfig.containsKey(filterCandidateKey)) {
					// get filter types for filterCandidateKey and check all types are the same
					Object filterConfig = filtersConfig.get(filterCandidateKey);
					SemiParsedFilterConfig parsedFilterConfig = ConfigParseUtil.parseFilterType(filterConfig, filterCandidateKey);
					// construct appropriate filter and set filters.valuesForField() values
						// - use <_enum> or <_lowercase> if specified
				}
				// iterate over all created filters and remove those who are suppressed
			}

			addFilter(searchFilters, ContentObjectFields.SYS_TYPE, filters.valuesForField(ContentObjectFields.SYS_TYPE));
			addFilter(searchFilters, ContentObjectFields.SYS_CONTENT_PROVIDER, filters.valuesForField(ContentObjectFields.SYS_CONTENT_PROVIDER));
			addFilter(searchFilters, ContentObjectFields.SYS_TAGS, filters.valuesForField(ContentObjectFields.SYS_TAGS));
			addFilter(searchFilters, ContentObjectFields.SYS_PROJECT, filters.valuesForField(ContentObjectFields.SYS_PROJECT));
			addFilter(searchFilters, ContentObjectFields.SYS_CONTRIBUTORS, filters.valuesForField(ContentObjectFields.SYS_CONTRIBUTORS));
			if (filters.getActivityDateInterval() != null) {
				RangeFilterBuilder range = new RangeFilterBuilder(ContentObjectFields.SYS_ACTIVITY_DATES);
					range.gte(DATE_TIME_FORMATTER_UTC.print(filters.getActivityDateInterval().getFromTimestamp())).includeLower(
						true);
				searchFilters.put(ContentObjectFields.SYS_ACTIVITY_DATES, range);
			} else if (filters.getActivityDateFrom() != null || filters.getActivityDateTo() != null) {
				RangeFilterBuilder range = new RangeFilterBuilder(ContentObjectFields.SYS_ACTIVITY_DATES);
				if (filters.getActivityDateFrom() != null) {
					range.gte(DATE_TIME_FORMATTER_UTC.print(filters.getActivityDateFrom())).includeLower(true);
				}
				if (filters.getActivityDateTo() != null) {
					range.lte(DATE_TIME_FORMATTER_UTC.print(filters.getActivityDateTo())).includeUpper(true);
				}
				searchFilters.put(ContentObjectFields.SYS_ACTIVITY_DATES, range);
			}
		}
		return searchFilters;
	}

	protected QueryBuilder applyCommonFilters(Map<String, FilterBuilder> searchFilters, QueryBuilder qb) {
		if (!searchFilters.isEmpty()) {
			return new FilteredQueryBuilder(qb, new AndFilterBuilder(searchFilters.values().toArray(
					new FilterBuilder[searchFilters.size()])));
		} else {
			return qb;
		}
	}

	private void addFilter(Map<String, FilterBuilder> searchFilters, String filterField, List<String> filterValues) {
		if (filterValues != null && !filterValues.isEmpty()) {
			searchFilters.put(filterField, new TermsFilterBuilder(filterField, filterValues));
		}
	}

	private void addFilter(Map<String, FilterBuilder> searchFilters, String filterField, String filterValue) {
		if (filterValue != null && !filterValue.isEmpty()) {
			searchFilters.put(filterField, new TermsFilterBuilder(filterField, filterValue));
		}
	}

	/**
	 * @param querySettings
	 * @param srb
	 */
	protected void handleFacetSettings(QuerySettings querySettings, Map<String, FilterBuilder> searchFilters,
			SearchRequestBuilder srb) {
		Map<String, Object> configuredFacets = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS);
		Set<String> requestedFacets = querySettings.getFacets();
		if (configuredFacets != null && !configuredFacets.isEmpty() && requestedFacets != null && !requestedFacets.isEmpty()) {
			for (String requestedFacet: requestedFacets) {
				Object facetConfig = configuredFacets.get(requestedFacet);
				if (facetConfig != null) {
					SemiParsedFacetConfig parsedFacetConfig = parseFacetType(facetConfig, requestedFacet);
					if ("terms".equals(parsedFacetConfig.getFacetType())) {
						int size;
						try {
							size = (int) parsedFacetConfig.getOptionalSettings().get("size");
						} catch (Exception e) {
							throw new SettingsException("Incorrect configuration of fulltext search facet field '" + requestedFacet
									+ "' in configuration document " + ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS
									+ ": Invalid value of [size] field.");
						}
						srb.addFacet(createTermsFacetBuilder(requestedFacet, parsedFacetConfig.getFieldName(), size, searchFilters));
						if (searchFilters != null && searchFilters.containsKey(parsedFacetConfig.getFieldName())) {
							if (parsedFacetConfig.isFiltered()) {
								// we filter over contributors so we have to add second facet which provide numbers for these
								// contributors because they can be out of normal facet due count limitation
								TermsFacetBuilder tb = new TermsFacetBuilder(requestedFacet + "_filter").field(parsedFacetConfig.getFieldName())
										.size(parsedFacetConfig.getFilteredSize()).global(true)
										.facetFilter(new AndFilterBuilder(filtersMapToArray(searchFilters)));
								srb.addFacet(tb);
							}
						}
					} else if ("date_histogram".equals(parsedFacetConfig.getFacetType())) {
						srb.addFacet(new DateHistogramFacetBuilder(requestedFacet).field(parsedFacetConfig.getFieldName()).interval(
								selectActivityDatesHistogramInterval(querySettings)));
					}
				}
			}
		}
	}

	/**
	 * Return (the first) name of fact that is built on top of "sys_type" field.
	 * 
	 * @return (the first) name of fact that is built on top of "sys_type" field.
	 */
	private String getFacetNameUsingSysTypeField() {
		Map<String, Object> configuredFacets = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS);
		if (configuredFacets != null && !configuredFacets.isEmpty()) {
			for (String facetName : configuredFacets.keySet()) {
				Object facetConfig = configuredFacets.get(facetName);
				if (facetConfig != null) {
					SemiParsedFacetConfig config = parseFacetType(facetConfig, facetName);
					if (ContentObjectFields.SYS_TYPE.equals(config.getFieldName())) {
						return facetName;
					}
				}
			}
		}
		return "";
	}

	/**
	 * For given set of facet names it returns only those using "date_histogram" facet type.
	 * 
	 * @param facetNames set of facet names to filter
	 * @return only those facets names using "date_histogram" facet type
	 */
	private Set<String> filterFacetNamesUsingDateHistogramFacetType(Set<String> facetNames) {
		Set<String> result = new LinkedHashSet<>();
		if (facetNames.size() > 0) {
			Map<String, Object> configuredFacets = configService.get(ConfigService.CFGNAME_SEARCH_FULLTEXT_FACETS_FIELDS);
			if (configuredFacets != null && !configuredFacets.isEmpty()) {
				for (String facetName : configuredFacets.keySet()) {
					Object facetConfig = configuredFacets.get(facetName);
					if (facetConfig != null) {
						SemiParsedFacetConfig config = parseFacetType(facetConfig, facetName);
						if ("date_histogram".equals(config.getFacetType())) {
							result.add(facetName);
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * Get additional fields to be added into search response.
	 * 
	 * @param querySettings for search
	 * @return map with additional fields, never null
	 */
	public Map<String, String> getSearchResponseAdditionalFields(QuerySettings querySettings) {
		Map<String, String> ret = new HashMap<>();
		Set<String> facets = querySettings.getFacets();
		if (facets != null) {
			Set<String> dateHistogramFacets = filterFacetNamesUsingDateHistogramFacetType(facets);
			// TODO: hack-ish modification for configurable facets
			String interval = null;
			for (String facetName : dateHistogramFacets) {
				if (interval == null) {
					interval = selectActivityDatesHistogramInterval(querySettings);
				}
				ret.put(facetName + "_interval", interval);
			}
		}
		return ret;
	}

	protected TermsFacetBuilder createTermsFacetBuilder(String facetName, String facetField, int size,
			Map<String, FilterBuilder> searchFilters) {

		TermsFacetBuilder tb = new TermsFacetBuilder(facetName).field(facetField).size(size).global(true);
		if (searchFilters != null && !searchFilters.isEmpty()) {
			FilterBuilder[] fb = filtersMapToArrayExcluding(searchFilters, facetField);
			if (fb != null && fb.length > 0)
				tb.facetFilter(new AndFilterBuilder(fb));
		}
		return tb;
	}

	@SuppressWarnings("incomplete-switch")
	protected static String selectActivityDatesHistogramInterval(QuerySettings querySettings) {
		Filters filters = querySettings.getFilters();
		if (filters != null) {
			if (filters.getActivityDateInterval() != null) {
				switch (filters.getActivityDateInterval()) {
				case YEAR:
				case QUARTER:
					return "week";
				case MONTH:
				case WEEK:
					return "day";
				case DAY:
					return "hour";
				}
			} else if (filters.getActivityDateFrom() != null || filters.getActivityDateTo() != null) {
				long from = 0;
				long to = 0;
				if (filters.getActivityDateTo() != null) {
					to = filters.getActivityDateTo().longValue();
				} else {
					to = System.currentTimeMillis();
				}
				if (filters.getActivityDateFrom() != null) {
					from = filters.getActivityDateFrom().longValue();
				}
				long interval = to - from;
				if (interval < 1000L * 60L * 60L) {
					return "minute";
				} else if (interval < 1000L * 60L * 60L * 24 * 2) {
					return "hour";
				} else if (interval < 1000L * 60L * 60L * 24 * 7 * 8) {
					return "day";
				} else if (interval < 1000L * 60L * 60L * 24 * 366) {
					return "week";
				}
			}
		}
		return "month";
	}

	protected static FilterBuilder[] filtersMapToArray(Map<String, FilterBuilder> filters) {
		return filtersMapToArrayExcluding(filters, null);
	}

	protected static FilterBuilder[] filtersMapToArrayExcluding(Map<String, FilterBuilder> filters, String filterToExclude) {
		List<FilterBuilder> builders = new ArrayList<>();
		if (filters != null) {
			for (String name : filters.keySet()) {
				if (filterToExclude == null || !filterToExclude.equals(name)) {
					builders.add(filters.get(name));
				}
			}
		}
		return builders.toArray(new FilterBuilder[builders.size()]);
	}

	/**
	 * @param querySettings
	 * @param srb request builder to set sorting for
	 * @see <a href="http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/search-request-sort.html">Elasticsearch 0.90 - Sort</a>
	 */
	protected void setSearchRequestSort(QuerySettings querySettings, SearchRequestBuilder srb) {
		if (querySettings.getSortBy() != null) {
			if (querySettings.getSortBy().equals(SortByValue.NEW)) {
				srb.addSort(ContentObjectFields.SYS_LAST_ACTIVITY_DATE, SortOrder.DESC);
			} else if (querySettings.getSortBy().equals(SortByValue.OLD)) {
				srb.addSort(ContentObjectFields.SYS_LAST_ACTIVITY_DATE, SortOrder.ASC);
			} else if (querySettings.getSortBy().equals(SortByValue.NEW_CREATION)) {
				srb.addSort(ContentObjectFields.SYS_CREATED, SortOrder.DESC);
			}
		}
	}

	/**
	 * @param querySettings
	 * @param srb request builder to set response content for
	 * @see <a href="http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/search-request-fields.html">Elasticsearch 0.90 - Fields</a>
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected void setSearchRequestFields(QuerySettings querySettings, SearchRequestBuilder srb) {

		// handle 'field' params to return configured fields only. Use default set of fields loaded from configuration.
		if (querySettings.getFields() != null) {
			srb.addFields((querySettings.getFields()).toArray(new String[querySettings.getFields().size()]));
		} else {
			Map<String, Object> cf = configService.get(ConfigService.CFGNAME_SEARCH_RESPONSE_FIELDS);
			if (cf != null && cf.containsKey(ConfigService.CFGNAME_SEARCH_RESPONSE_FIELDS)) {
				Object o = cf.get(ConfigService.CFGNAME_SEARCH_RESPONSE_FIELDS);
				if (o instanceof Collection) {
					srb.addFields(((Collection<String>) o).toArray(new String[((Collection) o).size()]));
				} else if (o instanceof String) {
					srb.addField((String) o);
				} else {
					throw new SettingsException(ConfigService.CFGNAME_SEARCH_RESPONSE_FIELDS
							+ " configuration document is invalid. Contact administrators please.");
				}
			}
		}
	}

	/**
	 * @param querySettings
	 * @param srb
	 * @link <a href="http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/search-request-from-size.html">Elasticsearch 0.90 - From/Size</a>
	 */
	protected void setSearchRequestFromSize(QuerySettings querySettings, SearchRequestBuilder srb) {
		QuerySettings.Filters filters = querySettings.getFilters();
		if (filters != null) {
			if (filters.getFrom() != null && filters.getFrom() >= 0) {
				srb.setFrom(filters.getFrom());
			}
			if (filters.getSize() != null && filters.getSize() >= 0) {
				int size = filters.getSize();
				if (size > RESPONSE_MAX_SIZE)
					size = RESPONSE_MAX_SIZE;
				srb.setSize(size);
			}
		}
	}

	/**
	 * Write info about used search hit into statistics. Validation is performed inside of this method to ensure given
	 * content was returned as hit of given search response.
	 * 
	 * @param uuid of search response hit was returned in
	 * @param contentId identifier of content used
	 * @param sessionId optional session id
	 * @return true if validation was successful so record was written
	 */
	public boolean writeSearchHitUsedStatisticsRecord(String uuid, String contentId, String sessionId) {
		Map<String, Object> conditions = new HashMap<String, Object>();
		conditions.put(StatsClientService.FIELD_RESPONSE_UUID, uuid);
		conditions.put(StatsClientService.FIELD_HITS_ID, contentId);
		if (statsClientService.checkStatisticsRecordExists(StatsRecordType.SEARCH, conditions)) {
			if (sessionId != null)
				conditions.put("session", sessionId);
			statsClientService.writeStatisticsRecord(StatsRecordType.SEARCH_HIT_USED, System.currentTimeMillis(), conditions);
			return true;
		} else {
			return false;
		}
	}
}
