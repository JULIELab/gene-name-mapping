/** 
 * GeneMapperException.java
 * 
 * Copyright (c) 2008, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: tomanek
 * 
 * Current version: 1.7 	
 * Since version:   1.7
 *
 * Creation date: Mar 11, 2008 
 * 
 * a generic exception type to be used with the gene mapper.
 **/

package de.julielab.jules.ae.genemapping.utils;

public class GeneMappingException extends Exception {


	/**
	 * 
	 */
	private static final long serialVersionUID = 7207114938075802444L;

	public GeneMappingException() {
		super();
	}
	
	public GeneMappingException(String s) {
		super(s);
	}

	public GeneMappingException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public GeneMappingException(String message, Throwable cause) {
		super(message, cause);
	}

	public GeneMappingException(Throwable cause) {
		super(cause);
	}

	
}
