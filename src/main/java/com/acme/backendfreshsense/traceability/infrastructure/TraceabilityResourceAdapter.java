package com.acme.backendfreshsense.traceability.infrastructure;

import com.acme.backendfreshsense.inventory.domain.repository.ProductRepository;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.DeviceJpaRepository;
import com.acme.backendfreshsense.traceability.application.TraceabilityResources;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class TraceabilityResourceAdapter implements TraceabilityResources {
    private final ProductRepository products;
    private final DeviceJpaRepository devices;

    TraceabilityResourceAdapter(ProductRepository products, DeviceJpaRepository devices) {
        this.products = products;
        this.devices = devices;
    }

    @Override
    public Optional<ProductSnapshot> findOwnedProduct(long productId, long ownerId) {
        return products.findByIdAndUserId(productId, ownerId)
                .map(product -> new ProductSnapshot(product.getName(), product.getCategory()));
    }

    @Override
    public Optional<DeviceReference> lockOwnedDevice(String deviceId, long ownerId) {
        return devices.findByDeviceIdAndUserId(deviceId, ownerId)
                .map(device -> new DeviceReference(device.getId(), device.getDeviceId()));
    }
}
