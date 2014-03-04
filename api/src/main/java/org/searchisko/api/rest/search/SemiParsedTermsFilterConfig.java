/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest.search;

/**
 * @author Lukas Vlcek
 */
public class SemiParsedTermsFilterConfig extends SemiParsedFilterConfig {

	private boolean lowercase = false;

	public boolean isLowercase() { return lowercase; }
	public void setLowercase(boolean value) { this.lowercase = value; }
}
