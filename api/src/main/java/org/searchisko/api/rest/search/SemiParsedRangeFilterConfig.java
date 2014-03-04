/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest.search;

import java.util.Collections;
import java.util.List;

/**
 * @author Lukas Vlcek
 */
public class SemiParsedRangeFilterConfig extends SemiParsedFilterConfig {

	List<String> suppress = Collections.emptyList();

	public List<String> getSuppressed() { return this.suppress; }
	public void setSuppressed(List<String> suppress) { this.suppress = suppress; }

	public Object getGte() {
		return null;
	}

	public Object getLte() {
		return null;
	}

	public String getEnum() {
		return null;
	}
}
