package com.acme.backendfreshsense.traceability.application;

import java.util.Optional;

public interface TraceabilityResources {
    record ProductSnapshot(String name, String category) {}
    record DeviceReference(long key, String deviceId) {}

    Optional<ProductSnapshot> findOwnedProduct(long productId, long ownerId);
    Optional<DeviceReference> lockOwnedDevice(String deviceId, long ownerId);
}
