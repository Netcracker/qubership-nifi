package org.qubership.cloud.nifi.quarkus.config.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.qubership.cloud.nifi.quarkus.config.ConsulClientConfiguration;

/**
 * Deployment processor to set consul ACL token provider bean as unremovable.
 */
public class ConsulTokenProviderProcessor {
    private static final String FEATURE = "consul-acl-token-provider";

    /**
     * Feature to provide.
     * @return feature to provide
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Registers additional beans as unremovable.
     * @param additionalBeans additional beans
     */
    @BuildStep
    public void registerAdditionalBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(ConsulClientConfiguration.class)
                .build());
    }
}
