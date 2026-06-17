package com.fraud.detection.service.model.enums;

/**
 * Lifecycle status of a FraudFlag once it has been raised.
 *
 * OPEN     — newly created, awaiting analyst review
 * REVIEWED — an analyst has acknowledged and assessed the flag
 */
public enum FlagStatus {
    OPEN,
    REVIEWED
}
