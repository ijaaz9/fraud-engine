package com.fraud.detection.exception;

/**
 * Thrown when a requested transaction ID does not exist in the system.
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(String transactionId) {
        super("Transaction [" + transactionId + "] not found");
    }
}
