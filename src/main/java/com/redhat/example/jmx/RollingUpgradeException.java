package com.redhat.example.jmx;

public class RollingUpgradeException extends RuntimeException {

	private static final long serialVersionUID = -2162038647649572066L;

	public RollingUpgradeException() {
		super();
	}

	public RollingUpgradeException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public RollingUpgradeException(String message, Throwable cause) {
		super(message, cause);
	}

	public RollingUpgradeException(String message) {
		super(message);
	}

	public RollingUpgradeException(Throwable cause) {
		super(cause);
	}

}
