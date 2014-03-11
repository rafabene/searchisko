DCP configuration - configuration of fields which are allowed for filters
==============================================================================

**configuration API id:** `search_fulltext_filter_fields`

Configuration of Elasticsearch [filters](http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/query-dsl-filters.html) in Searchisko.

The following filter types are supported:


### Terms filter

Uses [Terms filter](http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/query-dsl-terms-filter.html).

	"<filter_name>" : {
		"terms" : {
			"<field_name>" : [],
			<optional_settings>
		}
	}

### Range filter

Uses [Range filter](http://www.elasticsearch.org/guide/en/elasticsearch/reference/0.90/query-dsl-range-filter.html).

	"<filter_name>" : {
		"range" : {
			"<field_name>" : {
				gte: null,
				lte: mull,
				<optional_settings>
			}
		},
		"_suppress" : [<filter_name>, <filter_name>, ...],
		"_enum" : "<className>"
	}

Range filter in Elasticsearch allows for both upper and lower bounds to be set. However, filters in Searchisko map
to URL parameters which means we can pass only a single value per filter thus only one option (`gte` or `lte`) is
allowed per range filter configuration. This limitation fits well to schema where user sets bounds by different URL
parameters (for example `&to=2012-12-31&from=2010-01-01`). However, we can map both filters to the same document filed
which will result into a single Elasticsearch range filter to be instantiated with both upper and lower bounds set
correctly.

For example:

	"from" : {
		"range" : {
			"sys_activity_dates" : {
				"gte" : null
			}
		}
	},
	"to" : {
		"range" : {
			"sys_activity_dates" : {
				"lte" : null
			}
		}
	}

Note both `from` and `to` filters apply to **to same field name** `sys_activity_dates`. This has the following consequences:

- If only one of `from` or `to` URL parameters are set by the user then appropriate range filter is instantiated accordingly using only `gte` or `lte` option respectively.
- If, however, both URL parameters are present then again a single filter is instantiated but both upper and lower bounds are setup.

#### \<_suppress\>

Allows to specify that this filter suppress listed filters. For example if filter B is configured to suppress filter A
then if both filters A and B are provided by the client then filter A is ignored and only the filter B is used.

#### \<_enum\>

Maps to class name of [`enum`](http://docs.oracle.com/javase/7/docs/api/java/lang/Enum.html) type.
If specified then this class is used to transform user input value to output value for the filter.

Supported enum types:

- `org.searchisko.api.util.SearchUtils.PastIntervalValue`
  - input: `day`, `week`, `month`, `quarter` or `year`
  - output: a value in millis corresponding to `now - ${input}`

## Comprehensive Filter Configuration Example

Put everything together:

	{
		"project" : {
			"terms" : {
				"sys_project" : []
			}
		},
		"activity_date_from" : {
			"range" : {
				"sys_activity_dates" : {
					"gte" : null
				}
			}
		},
		"activity_date_to" : {
			"range" : {
				"sys_activity_dates" : {
					"lte" : null
				}
			}
		},
		"activity_date_interval" : {
			"range" : {
				"sys_activity_dates" : {
					"gte" : null
				}
			},
			"_replace" : ["activity_date_from", "activity_date_to"],
			"_enum" : "org.searchisko.api.util.SearchUtils.PastIntervalValue"
		}
	}

Using the configuration above the following URL query parts can be processed:

`&project=hibernate&project=weld&project=wildfly&activity_date_from=2012-01-01`

will result into ... TBD
