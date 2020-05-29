package de.julielab.jules.ae.genemapping.resources.util;

public class UncheckedGeneMapperResourcesException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8972989109910259769L;

	public UncheckedGeneMapperResourcesException() {
		super();
	}

	public UncheckedGeneMapperResourcesException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public UncheckedGeneMapperResourcesException(String message, Throwable cause) {
		super(message, cause);
	}

	public UncheckedGeneMapperResourcesException(String message) {
		super(message);
	}

	public UncheckedGeneMapperResourcesException(Throwable cause) {
		super(cause);
	}

}
