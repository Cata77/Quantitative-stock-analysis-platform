package com.quantplatform.screener.sectors;

/** The publication is not a complete published run, or the sector has no members in it. */
public class SectorNotFoundException extends RuntimeException {
    public SectorNotFoundException(String message) {
        super(message);
    }
}
