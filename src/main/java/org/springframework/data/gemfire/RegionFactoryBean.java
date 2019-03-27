/*
 * Copyright 2010-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.gemfire;

import static java.util.Arrays.stream;
import static org.springframework.data.gemfire.util.ArrayUtils.nullSafeArray;
import static org.springframework.data.gemfire.util.CollectionUtils.nullSafeCollection;
import static org.springframework.data.gemfire.util.CollectionUtils.nullSafeIterable;
import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalArgumentException;
import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalStateException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheListener;
import org.apache.geode.cache.CacheLoader;
import org.apache.geode.cache.CacheWriter;
import org.apache.geode.cache.CustomExpiry;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.DiskStore;
import org.apache.geode.cache.EvictionAction;
import org.apache.geode.cache.EvictionAttributes;
import org.apache.geode.cache.ExpirationAttributes;
import org.apache.geode.cache.GemFireCache;
import org.apache.geode.cache.PartitionAttributes;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.asyncqueue.AsyncEventQueue;
import org.apache.geode.cache.wan.GatewaySender;
import org.apache.geode.compression.Compressor;
import org.apache.geode.internal.cache.UserSpecifiedRegionAttributes;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.Resource;
import org.springframework.data.gemfire.client.ClientRegionFactoryBean;
import org.springframework.data.gemfire.config.annotation.RegionConfigurer;
import org.springframework.data.gemfire.eviction.EvictingRegionFactoryBean;
import org.springframework.data.gemfire.expiration.ExpiringRegionFactoryBean;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Abstract Spring {@link FactoryBean} base class extended by other SDG {@link FactoryBean FactoryBeans} used to
 * construct, configure and initialize peer {@link Region Regions}.
 *
 * This {@link FactoryBean} allows for very easy and flexible creation of peer {@link Region}.
 * For client {@link Region Regions}, however, see the {@link ClientRegionFactoryBean}.
 *
 * @author Costin Leau
 * @author David Turanski
 * @author John Blum
 * @see org.apache.geode.cache.Cache
 * @see org.apache.geode.cache.CacheListener
 * @see org.apache.geode.cache.CacheLoader
 * @see org.apache.geode.cache.CacheWriter
 * @see org.apache.geode.cache.DataPolicy
 * @see org.apache.geode.cache.EvictionAttributes
 * @see org.apache.geode.cache.GemFireCache
 * @see org.apache.geode.cache.PartitionAttributes
 * @see org.apache.geode.cache.Region
 * @see org.apache.geode.cache.RegionAttributes
 * @see org.apache.geode.cache.RegionFactory
 * @see org.apache.geode.cache.RegionShortcut
 * @see org.apache.geode.cache.Scope
 * @see org.apache.geode.cache.asyncqueue.AsyncEventQueue
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.context.SmartLifecycle
 * @see org.springframework.data.gemfire.RegionLookupFactoryBean
 * @see org.springframework.data.gemfire.client.ClientRegionFactoryBean
 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
 */
@SuppressWarnings("unused")
// TODO: Rename to PeerRegionFactoryBean in SD Lovelace
public abstract class RegionFactoryBean<K, V> extends RegionLookupFactoryBean<K, V>
		implements EvictingRegionFactoryBean, ExpiringRegionFactoryBean<K, V>, DisposableBean, SmartLifecycle {

	protected final Log log = LogFactory.getLog(getClass());

	private boolean close = true;
	private boolean destroy = false;
	private boolean running;

	private Boolean offHeap;
	private Boolean persistent;
	private Boolean statisticsEnabled;

	private AsyncEventQueue[] asyncEventQueues;

	private CacheListener<K, V>[] cacheListeners;

	private CacheLoader<K, V> cacheLoader;

	private CacheWriter<K, V> cacheWriter;

	private Class<K> keyConstraint;
	private Class<V> valueConstraint;

	private Compressor compressor;

	private CustomExpiry<K, V> customEntryIdleTimeout;
	private CustomExpiry<K, V> customEntryTimeToLive;

	private DataPolicy dataPolicy;

	private EvictionAttributes evictionAttributes;

	private ExpirationAttributes entryIdleTimeout;
	private ExpirationAttributes entryTimeToLive;
	private ExpirationAttributes regionIdleTimeout;
	private ExpirationAttributes regionTimeToLive;

	private GatewaySender[] gatewaySenders;

	private List<RegionConfigurer> regionConfigurers = Collections.emptyList();

	private RegionAttributes<K, V> attributes;

	private RegionConfigurer compositeRegionConfigurer = new RegionConfigurer() {

		@Override
		public void configure(String beanName, RegionFactoryBean<?, ?> bean) {
			nullSafeCollection(regionConfigurers)
				.forEach(regionConfigurer -> regionConfigurer.configure(beanName, bean));
		}
	};

	private RegionShortcut shortcut;

	private Resource snapshot;

	private Scope scope;

	private String diskStoreName;

	/**
	 * Creates a new {@link Region} with the given {@link String name}.
	 *
	 * @param gemfireCache reference to the {@link GemFireCache}.
	 * @param regionName {@link String name} of the new {@link Region}.
	 * @return a new {@link Region} with the given {@link String name}.
	 * @see org.apache.geode.cache.GemFireCache
	 * @see org.apache.geode.cache.Region
	 */
	@Override
	protected Region<K, V> createRegion(GemFireCache gemfireCache, String regionName) throws Exception {

		applyRegionConfigurers(regionName);

		verifyLockGrantorEligibility(getAttributes(), getScope());

		Cache cache = resolveCache(gemfireCache);

		RegionFactory<K, V> regionFactory = postProcess(configure(createRegionFactory(cache)));

		Region<K, V> region = newRegion(regionFactory, getParent(), regionName);

		return enableAsLockGrantor(region);
	}

	/* (non-Javadoc) */
	private void applyRegionConfigurers(String regionName) {
		applyRegionConfigurers(regionName, getCompositeRegionConfigurer());
	}

	/**
	 * Null-safe operation to apply the given array of {@link RegionConfigurer RegionConfigurers}
	 * to this {@link RegionFactoryBean}.
	 *
	 * @param regionName {@link String} containing the name of the {@link Region}.
	 * @param regionConfigurers array of {@link RegionConfigurer RegionConfigurers} applied
	 * to this {@link RegionFactoryBean}.
	 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
	 * @see #applyRegionConfigurers(String, Iterable)
	 */
	protected void applyRegionConfigurers(String regionName, RegionConfigurer... regionConfigurers) {
		applyRegionConfigurers(regionName, Arrays.asList(nullSafeArray(regionConfigurers, RegionConfigurer.class)));
	}

	/**
	 * Null-safe operation to apply the given {@link Iterable} of {@link RegionConfigurer RegionConfigurers}
	 * to this {@link RegionFactoryBean}.
	 *
	 * @param regionName {@link String} containing the name of the {@link Region}.
	 * @param regionConfigurers {@link Iterable} of {@link RegionConfigurer RegionConfigurers} applied
	 * to this {@link RegionFactoryBean}.
	 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
	 */
	protected void applyRegionConfigurers(String regionName, Iterable<RegionConfigurer> regionConfigurers) {
		StreamSupport.stream(nullSafeIterable(regionConfigurers).spliterator(), false)
			.forEach(regionConfigurer -> regionConfigurer.configure(regionName, this));
	}

	/* (non-Javadoc) */
	private Region<K, V> enableAsLockGrantor(Region<K, V> region) {

		Optional.ofNullable(region)
			.filter(it -> it.getAttributes().isLockGrantor())
			.ifPresent(Region::becomeLockGrantor);

		return region;
	}

	/* (non-Javadoc) */
	private Region<K, V> newRegion(RegionFactory<K, V> regionFactory, Region<?, ?> parentRegion, String regionName) {

		return Optional.ofNullable(parentRegion)
			.map(parent -> {
				logInfo("Creating Subregion [%1$s] with parent Region [%2$s]",
					regionName, parent.getName());

				return regionFactory.<K, V>createSubregion(parent, regionName);
			})
			.orElseGet(() -> {
				logInfo("Created Region [%1$s]", regionName);

				return regionFactory.create(regionName);
			});
	}

	/* (non-Javadoc) */
	private Cache resolveCache(GemFireCache gemfireCache) {

		return Optional.ofNullable(gemfireCache)
			.filter(cache -> cache instanceof Cache)
			.map(cache -> (Cache) cache)
			.orElseThrow(() -> newIllegalArgumentException("Peer Cache is required"));
	}

	/* (non-Javadoc) */
	private RegionAttributes<K, V> verifyLockGrantorEligibility(RegionAttributes<K, V> regionAttributes, Scope scope) {

		Optional.ofNullable(regionAttributes).ifPresent(attributes ->
			Assert.state(!attributes.isLockGrantor() || verifyScope(scope),
				"Lock Grantor only applies to GLOBAL Scoped Regions"));

		return regionAttributes;
	}

	/* (non-Javadoc) */
	private boolean verifyScope(Scope scope) {
		return (scope == null || Scope.GLOBAL.equals(scope));
	}

	/**
	 * Creates an instance of {@link RegionFactory} with the given {@link Cache} which is then used to construct,
	 * configure and initialize the {@link Region} specified by this {@link RegionFactoryBean}.
	 *
	 * @param cache reference to the {@link Cache}.
	 * @return a {@link RegionFactory} used to construct, configure and initialized the {@link Region} specified by
	 * this {@link RegionFactoryBean}.
	 * @see org.apache.geode.cache.Cache#createRegionFactory(org.apache.geode.cache.RegionShortcut)
	 * @see org.apache.geode.cache.Cache#createRegionFactory(org.apache.geode.cache.RegionAttributes)
	 * @see org.apache.geode.cache.Cache#createRegionFactory()
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected RegionFactory<K, V> createRegionFactory(Cache cache) {

		if (this.shortcut != null) {

			RegionFactory<K, V> regionFactory =
				mergeRegionAttributes(cache.createRegionFactory(this.shortcut), this.attributes);

			setDataPolicy(getDataPolicy(regionFactory, this.shortcut));

			return regionFactory;
		}
		else if (this.attributes != null) {
			return cache.createRegionFactory(this.attributes);
		}
		else {
			return cache.createRegionFactory();
		}
	}

	/**
	 * Configures the {@link RegionFactory} based on the configuration settings of this {@link RegionFactoryBean}.
	 *
	 * @param regionFactory {@link RegionFactory} to configure
	 * @return the given {@link RegionFactory}.
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected RegionFactory<K, V> configure(RegionFactory<K, V> regionFactory) {

		regionFactory.setStatisticsEnabled(resolveStatisticsEnabled());

		stream(nullSafeArray(this.asyncEventQueues, AsyncEventQueue.class))
			.forEach(asyncEventQueue -> regionFactory.addAsyncEventQueueId(asyncEventQueue.getId()));

		stream(nullSafeArray(this.cacheListeners, CacheListener.class)).forEach(regionFactory::addCacheListener);

		Optional.ofNullable(this.cacheLoader).ifPresent(regionFactory::setCacheLoader);

		Optional.ofNullable(this.cacheWriter).ifPresent(regionFactory::setCacheWriter);

		Optional.ofNullable(this.compressor).ifPresent(regionFactory::setCompressor);

		Optional.ofNullable(this.customEntryIdleTimeout).ifPresent(regionFactory::setCustomEntryIdleTimeout);

		Optional.ofNullable(this.customEntryTimeToLive).ifPresent(regionFactory::setCustomEntryTimeToLive);

		resolveDataPolicy(regionFactory, this.persistent, this.dataPolicy);

		Optional.ofNullable(this.diskStoreName)
			.filter(name -> isDiskStoreConfigurationAllowed())
			.ifPresent(regionFactory::setDiskStoreName);

		Optional.ofNullable(this.entryIdleTimeout).ifPresent(regionFactory::setEntryIdleTimeout);

		Optional.ofNullable(this.entryTimeToLive).ifPresent(regionFactory::setEntryTimeToLive);

		Optional.ofNullable(this.evictionAttributes).ifPresent(regionFactory::setEvictionAttributes);

		stream(nullSafeArray(this.gatewaySenders, GatewaySender.class))
			.forEach(gatewaySender -> regionFactory.addGatewaySenderId(((GatewaySender) gatewaySender).getId()));

		Optional.ofNullable(this.keyConstraint).ifPresent(regionFactory::setKeyConstraint);

		Optional.ofNullable(this.regionIdleTimeout).ifPresent(regionFactory::setRegionIdleTimeout);

		Optional.ofNullable(this.regionTimeToLive).ifPresent(regionFactory::setRegionTimeToLive);

		Optional.ofNullable(getScope()).ifPresent(regionFactory::setScope);

		Optional.ofNullable(this.valueConstraint).ifPresent(regionFactory::setValueConstraint);

		return regionFactory;
	}

	/**
	 * Post-process the {@link RegionFactory} used to create the {@link Region} specified by
	 * this {@link RegionFactoryBean} during initialization.
	 *
	 * The {@link RegionFactory} has been already constructed, configured and initialized by
	 * this {@link RegionFactoryBean} before this method gets invoked.
	 *
	 * @param regionFactory {@link RegionFactory} used to create the {@link Region}.
	 * @return the given {@link RegionFactory}.
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected RegionFactory<K, V> postProcess(RegionFactory<K, V> regionFactory) {

		Optional.ofNullable(this.offHeap).ifPresent(regionFactory::setOffHeap);

		return regionFactory;
	}

	/**
	 * Returns a reference to the Composite {@link RegionConfigurer} used to apply additional configuration
	 * to this {@link RegionFactoryBean} on Spring container initialization.
	 *
	 * @return the Composite {@link RegionConfigurer}.
	 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
	 */
	protected RegionConfigurer getCompositeRegionConfigurer() {
		return this.compositeRegionConfigurer;
	}

	/*
	 * (non-Javadoc)
	 *
	 * This method is not considered part of the RegionFactoryBean API and is strictly used for testing purposes!
	 *
	 * NOTE cannot pass RegionAttributes.class as the "targetType" in the second invocation of getFieldValue(..)
	 * since the "regionAttributes" field is naively declared as a instance of the implementation class type
	 * (RegionAttributesImpl) rather than the interface type (RegionAttributes)...
	 * so much for 'programming to interfaces' in GemFire!
	 *
	 * @see org.apache.geode.cache.RegionFactory#attrsFactory
	 * @see org.apache.geode.cache.AttributesFactory#regionAttributes
	 * @see org.apache.geode.cache.RegionAttributes#getDataPolicy
	 * @see org.apache.geode.cache.DataPolicy
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	DataPolicy getDataPolicy(RegionFactory regionFactory, RegionShortcut regionShortcut) {

		return getFieldValue(regionFactory, "attrsFactory", AttributesFactory.class)
			.flatMap(attributesFactory -> getFieldValue(attributesFactory,"regionAttributes", null))
			.map(regionAttributes -> ((RegionAttributes<K, V>) regionAttributes).getDataPolicy())
			.orElseGet(() -> RegionShortcutToDataPolicyConverter.INSTANCE.convert(regionShortcut));
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unchecked")
	private <T> Optional<T> getFieldValue(Object source, String fieldName, Class<T> targetType) {

		return Optional.ofNullable(source)
			.map(Object::getClass)
			.map(type -> ReflectionUtils.findField(type, fieldName, targetType))
			.map(field -> {
				ReflectionUtils.makeAccessible(field);
				return field;
			})
			.map(field -> (T) ReflectionUtils.getField(field, source));
	}

	/**
	 * Intelligently merges the given RegionAttributes with the configuration setting of the RegionFactory. This method
	 * is used to merge the RegionAttributes and PartitionAttributes with the RegionFactory that is created when the
	 * user specified a RegionShortcut.  This method gets called by the createRegionFactory method depending upon
	 * the value passed to the Cache.createRegionFactory() method (i.e. whether there was a RegionShortcut specified
	 * or not).
	 *
	 * @param <K> the Class type fo the Region key.
	 * @param <V> the Class type of the Region value.
	 * @param regionFactory the GemFire RegionFactory used to configure and create the Region that is the product
	 * of this RegionFactoryBean.
	 * @param regionAttributes the RegionAttributes containing the Region configuration settings to merge to the
	 * RegionFactory.
	 * @return the RegionFactory with the configuration settings of the RegionAttributes merged.
	 * @see #isUserSpecifiedEvictionAttributes(org.apache.geode.cache.RegionAttributes)
	 * @see #validateRegionAttributes(org.apache.geode.cache.RegionAttributes)
	 * @see org.apache.geode.cache.RegionAttributes
	 * @see org.apache.geode.cache.RegionFactory
	 */
	@SuppressWarnings("unchecked")
	protected <K, V> RegionFactory<K, V> mergeRegionAttributes(RegionFactory<K, V> regionFactory,
			RegionAttributes<K, V> regionAttributes) {

		if (regionAttributes != null) {

			// NOTE: this validation may not be strictly necessary depending on how the RegionAttributes were "created",
			validateRegionAttributes(regionAttributes);

			regionFactory.setCloningEnabled(regionAttributes.getCloningEnabled());
			regionFactory.setCompressor(regionAttributes.getCompressor());
			regionFactory.setConcurrencyChecksEnabled(regionAttributes.getConcurrencyChecksEnabled());
			regionFactory.setConcurrencyLevel(regionAttributes.getConcurrencyLevel());
			regionFactory.setCustomEntryIdleTimeout(regionAttributes.getCustomEntryIdleTimeout());
			regionFactory.setCustomEntryTimeToLive(regionAttributes.getCustomEntryTimeToLive());
			regionFactory.setDiskSynchronous(regionAttributes.isDiskSynchronous());
			regionFactory.setEnableAsyncConflation(regionAttributes.getEnableAsyncConflation());
			regionFactory.setEnableSubscriptionConflation(regionAttributes.getEnableSubscriptionConflation());
			regionFactory.setEntryIdleTimeout(regionAttributes.getEntryIdleTimeout());
			regionFactory.setEntryTimeToLive(regionAttributes.getEntryTimeToLive());

			// NOTE: EvictionAttributes are created by certain RegionShortcuts; need the null check!
			if (isUserSpecifiedEvictionAttributes(regionAttributes)) {
				regionFactory.setEvictionAttributes(regionAttributes.getEvictionAttributes());
			}

			regionFactory.setIgnoreJTA(regionAttributes.getIgnoreJTA());
			regionFactory.setIndexMaintenanceSynchronous(regionAttributes.getIndexMaintenanceSynchronous());
			regionFactory.setInitialCapacity(regionAttributes.getInitialCapacity());
			regionFactory.setKeyConstraint(regionAttributes.getKeyConstraint());
			regionFactory.setLoadFactor(regionAttributes.getLoadFactor());
			regionFactory.setLockGrantor(regionAttributes.isLockGrantor());
			regionFactory.setMembershipAttributes(regionAttributes.getMembershipAttributes());
			regionFactory.setMulticastEnabled(regionAttributes.getMulticastEnabled());
			regionFactory.setOffHeap(regionAttributes.getOffHeap());

			mergePartitionAttributes(regionFactory, regionAttributes);

			regionFactory.setPoolName(regionAttributes.getPoolName());
			regionFactory.setRegionIdleTimeout(regionAttributes.getRegionIdleTimeout());
			regionFactory.setRegionTimeToLive(regionAttributes.getRegionTimeToLive());
			regionFactory.setStatisticsEnabled(regionAttributes.getStatisticsEnabled());
			regionFactory.setSubscriptionAttributes(regionAttributes.getSubscriptionAttributes());
			regionFactory.setValueConstraint(regionAttributes.getValueConstraint());
		}

		return regionFactory;
	}

	/**
	 * Merges the {@link RegionAttributes} into the {@link RegionFactory}.
	 *
	 * @param regionFactory {@link RegionFactory} to configure.
	 * @param regionAttributes {@link RegionAttributes} used to configure the {@link RegionFactory}
	 * if not {@literal null}.
	 * @see org.apache.geode.cache.RegionAttributes
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected <K, V> void mergePartitionAttributes(RegionFactory<K, V> regionFactory,
			RegionAttributes<K, V> regionAttributes) {

		// NOTE: PartitionAttributes are created by certain RegionShortcuts; need the null check since RegionAttributes
		// can technically return null!
		// NOTE: most likely, the PartitionAttributes will never be null since the PartitionRegionFactoryBean always
		// sets a PartitionAttributesFactoryBean BeanBuilder on the RegionAttributesFactoryBean "partitionAttributes"
		// property.
		if (regionAttributes.getPartitionAttributes() != null) {
			PartitionAttributes partitionAttributes = regionAttributes.getPartitionAttributes();
			PartitionAttributesFactory partitionAttributesFactory = new PartitionAttributesFactory(partitionAttributes);
			RegionShortcutWrapper shortcutWrapper = RegionShortcutWrapper.valueOf(shortcut);

			// NOTE however, since the default value of redundancy is 0, we need to account for 'redundant'
			// RegionShortcut types, which specify a redundancy of 1.
			if (shortcutWrapper.isRedundant() && partitionAttributes.getRedundantCopies() == 0) {
				partitionAttributesFactory.setRedundantCopies(1);
			}

			// NOTE and, since the default value of localMaxMemory is based on the system memory, we need to account for
			// 'proxy' RegionShortcut types, which specify a local max memory of 0.
			if (shortcutWrapper.isProxy()) {
				partitionAttributesFactory.setLocalMaxMemory(0);
			}

			// NOTE internally, RegionFactory.setPartitionAttributes handles merging the PartitionAttributes, hooray!
			regionFactory.setPartitionAttributes(partitionAttributesFactory.create());
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * This method is not part of the RegionFactoryBean API and is strictly used for testing purposes!
	 *
	 * @see org.apache.geode.cache.AttributesFactory#validateAttributes(:RegionAttributes)
	 */
	@SuppressWarnings("deprecation")
	void validateRegionAttributes(RegionAttributes regionAttributes) {
		org.apache.geode.cache.AttributesFactory.validateAttributes(regionAttributes);
	}

	/*
	 * (non-Javadoc)
	 *
	 * This method is not part of the RegionFactoryBean API and is strictly used for testing purposes!
	 *
	 * NOTE unfortunately, must resort to using a GemFire internal class, ugh!
	 *
	 * @see org.apache.geode.internal.cache.UserSpecifiedRegionAttributes#hasEvictionAttributes
	 */
	boolean isUserSpecifiedEvictionAttributes(final RegionAttributes regionAttributes) {
		return (regionAttributes instanceof UserSpecifiedRegionAttributes
			&& ((UserSpecifiedRegionAttributes) regionAttributes).hasEvictionAttributes());
	}

	/* (non-Javadoc) */
	private boolean isDiskStoreConfigurationAllowed() {

		boolean allow = StringUtils.hasText(this.diskStoreName);

		allow &= (getDataPolicy().withPersistence() || (getAttributes() != null
			&& getAttributes().getEvictionAttributes() != null
			&& EvictionAction.OVERFLOW_TO_DISK.equals(attributes.getEvictionAttributes().getAction())));

		return allow;
	}

	/**
	 * Returns true when the user explicitly specified a value for the persistent attribute and it is true.  If the
	 * persistent attribute was not explicitly specified, then the persistence setting is implicitly undefined
	 * and will be determined by the Data Policy.
	 *
	 * @return true when the user specified an explicit value for the persistent attribute and it is true;
	 * false otherwise.
	 * @see #isNotPersistent()
	 * @see #isPersistentUnspecified()
	 */
	protected boolean isPersistent() {
		return Boolean.TRUE.equals(persistent);
	}

	/**
	 * Determines whether the user explicitly set the 'persistent' attribute or not.
	 *
	 * @return a boolean value indicating whether the user explicitly set the 'persistent' attribute to true or false.
	 * @see #isPersistent()
	 * @see #isNotPersistent()
	 */
	protected boolean isPersistentUnspecified() {
		return (persistent == null);
	}

	/**
	 * Returns true when the user explicitly specified a value for the persistent attribute and it is false.  If the
	 * persistent attribute was not explicitly specified, then the persistence setting is implicitly undefined
	 * and will be determined by the Data Policy.
	 *
	 * @return true when the user specified an explicit value for the persistent attribute and it is false;
	 * false otherwise.
	 * @see #isPersistent()
	 * @see #isPersistentUnspecified()
	 */
	protected boolean isNotPersistent() {
		return Boolean.FALSE.equals(persistent);
	}

	/**
	 * Validates that the settings for Data Policy and the 'persistent' attribute in &lt;gfe:*-region&gt; elements
	 * are compatible.
	 *
	 * @param resolvedDataPolicy the GemFire Data Policy resolved form the Spring GemFire XML namespace configuration
	 * meta-data.
	 * @see #isPersistent()
	 * @see #isNotPersistent()
	 * @see org.apache.geode.cache.DataPolicy
	 */
	protected void assertDataPolicyAndPersistentAttributesAreCompatible(DataPolicy resolvedDataPolicy) {

		if (resolvedDataPolicy.withPersistence()) {
			Assert.isTrue(isPersistentUnspecified() || isPersistent(), String.format(
				"Data Policy [%s] is invalid when persistent is false.", resolvedDataPolicy));
		}
		else {
			// NOTE otherwise, the Data Policy is not persistent, so...
			Assert.isTrue(isPersistentUnspecified() || isNotPersistent(), String.format(
				"Data Policy [%s] is invalid when persistent is true.", resolvedDataPolicy));
		}
	}

	/**
	 * Validates and sets the Data Policy on the RegionFactory used to create and configure the Region from this
	 * FactoryBean.
	 *
	 * @param regionFactory the RegionFactory used by this FactoryBean to create and configure the Region.
	 * @param persistent a boolean value indicating whether the Region should be persistent and persist it's
	 * data to disk.
	 * @param dataPolicy the configured Data Policy for the Region.
	 * @see #resolveDataPolicy(org.apache.geode.cache.RegionFactory, Boolean, String)
	 * @see org.apache.geode.cache.DataPolicy
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected void resolveDataPolicy(RegionFactory<K, V> regionFactory, Boolean persistent, DataPolicy dataPolicy) {

		if (dataPolicy != null) {
			assertDataPolicyAndPersistentAttributesAreCompatible(dataPolicy);
			regionFactory.setDataPolicy(dataPolicy);
			setDataPolicy(dataPolicy);
		}
		else {
			resolveDataPolicy(regionFactory, persistent, (String) null);
		}
	}

	/**
	 * Validates the configured Data Policy and may override it, taking into account the 'persistent' attribute
	 * and constraints for the Region type.
	 *
	 * @param regionFactory the GemFire RegionFactory used to create the desired Region.
	 * @param persistent a boolean value indicating whether the Region should persist it's data to disk.
	 * @param dataPolicy requested Data Policy as set by the user in the Spring GemFire configuration meta-data.
	 * @see org.apache.geode.cache.DataPolicy
	 * @see org.apache.geode.cache.RegionFactory
	 */
	protected void resolveDataPolicy(RegionFactory<K, V> regionFactory, Boolean persistent, String dataPolicy) {

		if (dataPolicy != null) {

			DataPolicy resolvedDataPolicy = new DataPolicyConverter().convert(dataPolicy);

			Assert.notNull(resolvedDataPolicy, String.format("Data Policy [%s] is invalid.", dataPolicy));
			assertDataPolicyAndPersistentAttributesAreCompatible(resolvedDataPolicy);

			regionFactory.setDataPolicy(resolvedDataPolicy);
			setDataPolicy(resolvedDataPolicy);
		}
		else {

			DataPolicy regionAttributesDataPolicy = getDataPolicy(getAttributes(), DataPolicy.DEFAULT);
			DataPolicy resolvedDataPolicy = (isPersistent() && DataPolicy.DEFAULT.equals(regionAttributesDataPolicy)
				? DataPolicy.PERSISTENT_REPLICATE : regionAttributesDataPolicy);

			assertDataPolicyAndPersistentAttributesAreCompatible(resolvedDataPolicy);

			regionFactory.setDataPolicy(resolvedDataPolicy);
			setDataPolicy(resolvedDataPolicy);
		}
	}

	/* (non-Javadoc) */
	private DataPolicy getDataPolicy(RegionAttributes regionAttributes, DataPolicy defaultDataPolicy) {
		return Optional.ofNullable(regionAttributes).map(RegionAttributes::getDataPolicy).orElse(defaultDataPolicy);
	}

	/**
	 * Closes and destroys the {@link Region}.
	 *
	 * @throws Exception if {@code destroy()} fails.
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see org.apache.geode.cache.Region#close()
	 * @see org.apache.geode.cache.Region#destroyRegion()
	 */
	@Override
	public void destroy() throws Exception {

		Optional.ofNullable(getObject()).ifPresent(region -> {
			if (this.close) {
				if (!region.getRegionService().isClosed()) {
					try {
						region.close();
					}
					catch (Exception ignore) {
					}
				}

			}

			if (this.destroy) {
				region.destroyRegion();
			}
		});
	}

	/**
	 * Configures an array of {@link AsyncEventQueue AsyncEventQueues} for this {@link Region} used to perform
	 * asynchronous data access operations, e.g. {@literal asynchronous write-behind}.
	 *
	 * @param asyncEventQueues array of {@link AsyncEventQueue AsyncEventQueues} used by this {@link Region}
	 * to perform asynchronous data access operations.
	 * @see org.apache.geode.cache.asyncqueue.AsyncEventQueue
	 */
	public void setAsyncEventQueues(AsyncEventQueue[] asyncEventQueues) {
		this.asyncEventQueues = asyncEventQueues;
	}

	/**
	 * Sets the {@link RegionAttributes} used to configure this {@link Region}.
	 *
	 * Specifying {@link RegionAttributes} allows maximum control in specifying various {@link Region} settings.
	 * Used only when the {@link Region} is created and not when the {@link Region} is looked up.
	 *
	 * @param attributes {@link RegionAttributes} used to configure this {@link Region}.
	 * @see org.apache.geode.cache.RegionAttributes
	 */
	public void setAttributes(RegionAttributes<K, V> attributes) {
		this.attributes = attributes;
	}

	/**
	 * Returns the {@link RegionAttributes} used to configure this {@link Region}.
	 *
	 * @return the {@link RegionAttributes} used to configure this {@link Region}.
	 * @see org.apache.geode.cache.RegionAttributes
	 */
	public RegionAttributes<K, V> getAttributes() {
		return Optional.ofNullable(getRegion()).map(Region::getAttributes).orElse(this.attributes);
	}

	/**
	 * Configures {@link CacheListener CacheListeners} used to listen for entry events on this {@link Region}.
	 *
	 * Used only when a new {@link Region} is created and not {@link #isLookupEnabled() looked up}.
	 *
	 * Overrides the {@link Region} settings specified in {@link RegionAttributes}
	 * set with {@link #setAttributes(RegionAttributes)}.
	 *
	 * @param cacheListeners array {@link CacheListener CacheListeners} to register with this {@link Region}.
	 * @see org.apache.geode.cache.CacheListener
	 */
	public void setCacheListeners(CacheListener<K, V>[] cacheListeners) {
		this.cacheListeners = cacheListeners;
	}

	/**
	 * Configures the {@link CacheLoader} used by this {@link Region} to perform {@literal synchronous read-through}
	 * data access operations to an underlying, external data source.
	 *
	 * Used only when a new {@link Region} is created and not {@link #isLookupEnabled() looked up}.
	 *
	 * Overrides the {@link Region} settings specified in {@link RegionAttributes}
	 * set with {@link #setAttributes(RegionAttributes)}.
	 *
	 * @param cacheLoader {@link CacheLoader} to register for this {@link Region}.
	 * @see org.apache.geode.cache.CacheLoader
	 */
	public void setCacheLoader(CacheLoader<K, V> cacheLoader) {
		this.cacheLoader = cacheLoader;
	}

	/**
	 * Configures the {@link CacheWriter} used by this {@link Region} to perform {@literal synchronous write-through}
	 * data access operations to an underlying, external data source.
	 *
	 * Used only when a new {@link Region} is created and not {@link #isLookupEnabled() looked up}.
	 *
	 * Overrides the {@link Region} settings specified in {@link RegionAttributes}
	 * set with {@link #setAttributes(RegionAttributes)}.
	 *
	 * @param cacheWriter {@link CacheWriter} to register for this {@link Region}.
	 * @see org.apache.geode.cache.CacheWriter
	 */
	public void setCacheWriter(CacheWriter<K, V> cacheWriter) {
		this.cacheWriter = cacheWriter;
	}

	/**
	 * Configure whether to close this {@literal Region} during shutdown.
	 *
	 * Defaults to {@literal true}.
	 *
	 * @param close boolean value indicating whether this {@link Region} should be closed during shutdown.
	 * @see #setDestroy(boolean)
	 */
	public void setClose(boolean close) {
		this.close = close;
	}

	/**
	 * Configures the {@link Compressor} used to compress this {@link Region Region's} data.
	 *
	 * @param compressor {@link Compressor} used to compress this {@link Region Region's} data.
	 * @see org.apache.geode.compression.Compressor
	 */
	public void setCompressor(Compressor compressor) {
		this.compressor = compressor;
	}

	public void setCustomEntryIdleTimeout(CustomExpiry<K, V> customEntryIdleTimeout) {
		this.customEntryIdleTimeout = customEntryIdleTimeout;
	}

	public void setCustomEntryTimeToLive(CustomExpiry<K, V> customEntryTimeToLive) {
		this.customEntryTimeToLive = customEntryTimeToLive;
	}

	/**
	 * Configure whether to destroy this {@link Region} during shutdown.
	 *
	 * Defaults to {@literal false}.
	 *
	 * @param destroy value indicating whether this {@link Region} should be destroyed during shutdown.
	 * @see #setClose(boolean)
	 */
	public void setDestroy(boolean destroy) {
		this.destroy = destroy;
	}

	/**
	 * Configure the {@link DataPolicy} for this {@link Region}.
	 *
	 * @param dataPolicy {@link DataPolicy} used when configuring this {@link Region}.
	 * @see org.apache.geode.cache.DataPolicy
	 * @since 1.4.0
	 */
	public void setDataPolicy(DataPolicy dataPolicy) {
		this.dataPolicy = dataPolicy;
	}

	/**
	 * Configures the {@link DataPolicy} for this {@link Region} as a {@link String}.
	 *
	 * @param dataPolicyName {@link String} containing the name of the {@link DataPolicy},
	 * (e.g. {@literal PARTITION} or {@literal REPLICATE}, etc).
	 * @see #setDataPolicy(org.apache.geode.cache.DataPolicy)
	 * @deprecated as of 1.4.0; use setDataPolicy(:DataPolicy) instead.
	 */
	@Deprecated
	public void setDataPolicy(String dataPolicyName) {
		setDataPolicy(new DataPolicyConverter().convert(dataPolicyName));
	}

	/**
	 * Returns resolved {@link DataPolicy} as configured with the {@link RegionFactory}
	 * when creating this {@link Region}.
	 *
	 * @return the configured, resolved {@link DataPolicy} used by this {@link Region}.
	 * @throws IllegalStateException if the {@link DataPolicy} has not been configured
	 * or is not resolvable.
	 * @see org.apache.geode.cache.DataPolicy
	 */
	public DataPolicy getDataPolicy() {
		return Optional.ofNullable(this.dataPolicy)
			.orElseThrow(() -> newIllegalStateException("Data Policy has not been properly resolved yet"));
	}

	/**
	 * Configures the {@link String name} of the {@link DiskStore} used by this {@link Region}
	 * for overflow and/or persistence.
	 *
	 * @param diskStoreName {@link String} containing the name of the {@link DiskStore} bean
	 * configured for this {@link Region}.
	 */
	public void setDiskStoreName(String diskStoreName) {
		this.diskStoreName = diskStoreName;
	}

	// TODO: review/add Javadoc from here forward...

	public void setEntryIdleTimeout(ExpirationAttributes entryIdleTimeout) {
		this.entryIdleTimeout = entryIdleTimeout;
	}

	public void setEntryTimeToLive(ExpirationAttributes entryTimeToLive) {
		this.entryTimeToLive = entryTimeToLive;
	}

	public void setEvictionAttributes(EvictionAttributes evictionAttributes) {
		this.evictionAttributes = evictionAttributes;
	}

	/**
	 * Configures the {@link GatewaySender GatewaySenders} used to send data and events from this {@link Region}
	 * to a corresponding {@link Region} in a remote cluster/site.
	 *
	 * @param gatewaySenders {@link GatewaySender GatewaySenders} used to send data and events from this {@link Region}
	 * to a corresponding {@link Region} in a remote cluster/site.
	 * @see org.apache.geode.cache.wan.GatewaySender
	 */
	public void setGatewaySenders(GatewaySender[] gatewaySenders) {
		this.gatewaySenders = gatewaySenders;
	}

	/**
	 * Configures whether to enable this {@link Region} with the ability to store data in {@literal off-heap memory}.
	 *
	 * @param offHeap {@link Boolean} value indicating whether to enable {@literal off-heap memory}
	 * for this {@link Region}.
	 * @see org.apache.geode.cache.RegionFactory#setOffHeap(boolean)
	 */
	public void setOffHeap(Boolean offHeap) {
		this.offHeap = offHeap;
	}

	/**
	 * Returns a {@link Boolean} value indicating whether {@literal off-heap memory} was enabled for this {@link Region}.
	 *
	 * {@literal Off-heap memory} will be enabled if this method returns a {@literal non-null} {@link Boolean} value
	 * evaluating to {@literal true}.
	 *
	 * @return a {@link Boolean} value indicating whether {@literal off-heap memory} is enabled for this {@link Region}.
	 */
	public Boolean getOffHeap() {
		return this.offHeap;
	}

	/**
	 * Returns a boolean value indicating whether {@literal off-heap memory} has been enabled for this {@link Region}.
	 *
	 * @return a {@literal boolean} value indicating whether {@literal off-heap memory} has been enabled
	 * for this {@link Region}.
	 * @see #getOffHeap()
	 */
	public boolean isOffHeap() {
		return Boolean.TRUE.equals(getOffHeap());
	}

	/**
	 * Configures the {@link Class key constraint} used to enforce key {@link Class types} for this {@link Region}.
	 *
	 * @param keyConstraint {@link Class} specifying the key type constraint for this {@link Region}.
	 * @see org.apache.geode.cache.RegionFactory#setKeyConstraint(Class)
	 * @see org.apache.geode.cache.RegionAttributes#getKeyConstraint()
	 * @see java.lang.Class
	 */
	public void setKeyConstraint(Class<K> keyConstraint) {
		this.keyConstraint = keyConstraint;
	}

	/**
	 * Configures whether to enable {@literal persistence} for this {@link Region}.
	 *
	 * When {@literal persistence} is enable, then data in the {@link Region} is persisted to disk
	 * using the configured, specified {@link DiskStore}, or the {@literal DEFAULT} {@link DiskStore}
	 * if a {@link DiskStore} was not explicitly configured.
	 *
	 * @param persistent {@link Boolean} value indicating whether to enaable {@literal persistence}
	 * for this {@link Region}.
	 */
	public void setPersistent(Boolean persistent) {
		this.persistent = persistent;
	}

	/**
	 * Null-safe operation to set an array of {@link RegionConfigurer RegionConfigurers} used to apply
	 * additional configuration to this {@link RegionFactoryBean} when using Annotation-based configuration.
	 *
	 * @param regionConfigurers array of {@link RegionConfigurer RegionConfigurers} used to apply
	 * additional configuration to this {@link RegionFactoryBean}.
	 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
	 * @see #setRegionConfigurers(List)
	 */
	public void setRegionConfigurers(RegionConfigurer... regionConfigurers) {
		setRegionConfigurers(Arrays.asList(nullSafeArray(regionConfigurers, RegionConfigurer.class)));
	}

	/**
	 * Null-safe operation to set an {@link Iterable} of {@link RegionConfigurer RegionConfigurers} used to apply
	 * additional configuration to this {@link RegionFactoryBean} when using Annotation-based configuration.
	 *
	 * @param regionConfigurers {@link Iterable} of {@link RegionConfigurer RegionConfigurers} used to apply
	 * additional configuration to this {@link RegionFactoryBean}.
	 * @see org.springframework.data.gemfire.config.annotation.RegionConfigurer
	 */
	public void setRegionConfigurers(List<RegionConfigurer> regionConfigurers) {
		this.regionConfigurers = Optional.ofNullable(regionConfigurers).orElseGet(Collections::emptyList);
	}

	public void setRegionIdleTimeout(ExpirationAttributes regionIdleTimeout) {
		this.regionIdleTimeout = regionIdleTimeout;
	}

	public void setRegionTimeToLive(ExpirationAttributes regionTimeToLive) {
		this.regionTimeToLive = regionTimeToLive;
	}

	/**
	 * Configures the {@link Region Region's} {@link Scope}, which affects data distribution
	 * and acknowledgement strategy (useful in consistency) for the {@link Region}.
	 *
	 * @param scope {@link Scope} used to configure the {@link Region Region's} data distribution
	 * and acknowledgement strategy.
	 * @see org.apache.geode.cache.Scope
	 */
	public void setScope(Scope scope) {
		this.scope = scope;
	}

	/**
	 * Returns the configured {@link Scope} of the {@link Region} affecting data distribution
	 * and acknowledgement strategy (useful in consistency) for the {@link Region}.
	 *
	 * @return the configured {@link Scope} of the {@link Region}.
	 * @see org.apache.geode.cache.Scope
	 */
	public Scope getScope() {
		return this.scope;
	}

	/**
	 * Configures the {@link Region} with the given {@link RegionShortcut}.
	 *
	 * @param shortcut {@link RegionShortcut} used to configure pre-defined defaults for the {@link Region}.
	 * @see org.apache.geode.cache.RegionShortcut
	 */
	public void setShortcut(RegionShortcut shortcut) {
		this.shortcut = shortcut;
	}

	/**
	 * Returns the configured {@link RegionShortcut}.
	 *
	 * @return the configured {@link RegionShortcut}.
	 * @see org.apache.geode.cache.RegionShortcut
	 */
	public RegionShortcut getShortcut() {
		return this.shortcut;
	}

	public void setStatisticsEnabled(Boolean statisticsEnabled) {
		this.statisticsEnabled = statisticsEnabled;
	}

	public Boolean getStatisticsEnabled() {
		return this.statisticsEnabled;
	}

	public boolean isStatisticsEnabled() {
		return Boolean.TRUE.equals(getStatisticsEnabled());
	}

	protected boolean resolveStatisticsEnabled() {

		return isStatisticsEnabled()
			|| this.customEntryIdleTimeout != null
			|| this.customEntryTimeToLive != null
			|| this.entryIdleTimeout != null
			|| this.entryTimeToLive != null
			|| this.regionIdleTimeout != null
			|| this.regionTimeToLive != null
			|| Optional.ofNullable(getAttributes())
				.map(RegionAttributes::getStatisticsEnabled)
				.orElse(false);
	}

	/**
	 * Configures the {@link Class value constraint} used to enforce value {@link Class types} for this {@link Region}.
	 *
	 * @param valueConstraint {@link Class} specifying the value type constraint for this {@link Region}.
	 * @see org.apache.geode.cache.RegionFactory#setValueConstraint(Class)
	 * @see org.apache.geode.cache.RegionAttributes#getValueConstraint()
	 * @see java.lang.Class
	 */
	public void setValueConstraint(Class<V> valueConstraint) {
		this.valueConstraint = valueConstraint;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	@SuppressWarnings("all")
	public void start() {

		if (!ObjectUtils.isEmpty(gatewaySenders)) {
			synchronized (gatewaySenders) {
				for (Object obj : gatewaySenders) {
					GatewaySender gatewaySender = (GatewaySender) obj;
					if (!(gatewaySender.isManualStart() || gatewaySender.isRunning())) {
						gatewaySender.start();
					}
				}
			}
		}

		this.running = true;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void stop(Runnable callback) {
		stop();
		callback.run();
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void stop() {

		if (!ObjectUtils.isEmpty(gatewaySenders)) {
			synchronized (gatewaySenders) {
				for (Object gatewaySender : gatewaySenders) {
					((GatewaySender) gatewaySender).stop();
				}
			}
		}

		this.running = false;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public boolean isRunning() {
		return this.running;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public boolean isAutoStartup() {
		return true;
	}
}
