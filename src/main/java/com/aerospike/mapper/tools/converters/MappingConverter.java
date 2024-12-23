package com.aerospike.mapper.tools.converters;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.mapper.tools.ClassCache;
import com.aerospike.mapper.tools.ClassCacheEntry;
import com.aerospike.mapper.tools.DeferredObjectLoader;
import com.aerospike.mapper.tools.DeferredObjectLoader.DeferredObjectSetter;
import com.aerospike.mapper.tools.IBaseAeroMapper;
import com.aerospike.mapper.tools.LoadedObjectResolver;
import com.aerospike.mapper.tools.ThreadLocalKeySaver;
import com.aerospike.mapper.tools.TypeMapper;
import com.aerospike.mapper.tools.utils.MapperUtils;
import com.aerospike.mapper.tools.utils.TypeUtils;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MappingConverter {

    private final IBaseAeroMapper mapper;
    private final IAerospikeClient aerospikeClient;

    public MappingConverter(IBaseAeroMapper mapper, IAerospikeClient aerospikeClient) {
        this.mapper = mapper;
        this.aerospikeClient = aerospikeClient;
    }

    /**
     * Translate a Java object to an Aerospike format object. Note that this could potentially have performance issues as
     * the type information of the passed object must be determined on every call.
     *
     * @param obj A given Java object.
     * @return An Aerospike format object.
     */
    public Object translateToAerospike(Object obj) {
        if (obj == null) {
            return null;
        }
        TypeMapper thisMapper = TypeUtils.getMapper(obj.getClass(), TypeUtils.AnnotatedType.getDefaultAnnotateType(), mapper);
        return thisMapper == null ? obj : thisMapper.toAerospikeFormat(obj);
    }

    /**
     * Translate an Aerospike object to a Java object. Note that this could potentially have performance issues as
     * the type information of the passed object must be determined on every call.
     *
     * @param obj A given Java object.
     * @return An Aerospike format object.
     */
    @SuppressWarnings("unchecked")
    public <T> T translateFromAerospike(@NotNull Object obj, @NotNull Class<T> expectedClazz) {
        TypeMapper thisMapper = TypeUtils.getMapper(expectedClazz, TypeUtils.AnnotatedType.getDefaultAnnotateType(), mapper);
        T result = (T) (thisMapper == null ? obj : thisMapper.fromAerospikeFormat(obj));
        resolveDependencies(ClassCache.getInstance().loadClass(expectedClazz, mapper));
        return result;
    }

    // --------------------------------------------------------------------------------------------------
    // The following are convenience methods to convert objects to / from lists / maps / records in case
    // it is needed to perform this operation manually. They will not be needed in most use cases.
    // --------------------------------------------------------------------------------------------------

    /**
     * Given a record loaded from Aerospike and a class type, attempt to convert the record to
     * an instance of the passed class.
     *
     * @param clazz  The class type to convert the Aerospike record to.
     * @param record The Aerospike record to convert.
     * @return A virtual list.
     */
    public <T> T convertToObject(Class<T> clazz, Key key, Record record) {
        return convertToObject(clazz, key, record, null);
    }

    /**
     * Given a record loaded from Aerospike and a class type, attempt to convert the record to
     * an instance of the passed class.
     *
     * @param clazz  The class type to convert the Aerospike record to.
     * @param record The Aerospike record to convert.
     * @param entry  The entry that holds information on how to store the provided class.
     * @return A virtual list.
     */
    public <T> T convertToObject(Class<T> clazz, Key key, Record record, ClassCacheEntry<T> entry) {
        return this.convertToObject(clazz, key, record, entry, true);
    }

    /**
     * This method should not be used, it is public only to allow mappers to see it.
     */
    public <T> T convertToObject(Class<T> clazz, Key key, Record record, ClassCacheEntry<T> entry, boolean resolveDependencies) {
        if (entry == null) {
            entry = ClassCache.getInstance().loadClass(clazz, mapper);
        }
        T result = entry.constructAndHydrate(key, record);
        if (resolveDependencies) {
            resolveDependencies(entry);
        }
        return result;
    }

    /**
     * Given a list of records loaded from Aerospike and a class type, attempt to convert the records to
     * an instance of the passed class.
     *
     * @param clazz  The class type to convert the Aerospike record to.
     * @param record The Aerospike records to convert.
     * @return A virtual list.
     * @throws AerospikeException an AerospikeException will be thrown in case of an encountering a ReflectiveOperationException.
     */
    public <T> T convertToObject(Class<T> clazz, List<Object> record) {
        return this.convertToObject(clazz, record, true);
    }

    /**
     * This method should not be used, it is public only to allow mappers to see it.
     */
    public <T> T convertToObject(Class<T> clazz, List<Object> record, boolean resolveDependencies) {
        try {
            ClassCacheEntry<T> entry = ClassCache.getInstance().loadClass(clazz, mapper);
            T result;
            result = clazz.getConstructor().newInstance();
            entry.hydrateFromList(record, result);
            if (resolveDependencies) {
                resolveDependencies(entry);
            }
            return result;
        } catch (ReflectiveOperationException e) {
            throw new AerospikeException(e);
        }
    }

    /**
     * Given a map of records loaded from Aerospike and a class type, attempt to convert the records to
     * an instance of the passed class.
     *
     * @param clazz  The class type to convert the Aerospike record to.
     * @param record The Aerospike records to convert.
     * @return A virtual list.
     * @throws AerospikeException an AerospikeException will be thrown in case of an encountering a ReflectiveOperationException.
     */
    public <T> T convertToObject(Class<T> clazz, Map<String, Object> record) {
        ClassCacheEntry<T> entry = ClassCache.getInstance().loadClass(clazz, mapper);
        return entry.constructAndHydrate(record);
    }

    /**
     * Given an instance of a class (of any type), convert its properties to a list
     *
     * @param instance The instance of a class (of any type).
     * @return a List of the properties of the given instance.
     */
    @SuppressWarnings("unchecked")
    public <T> List<Object> convertToList(@NotNull T instance) {
        ClassCacheEntry<T> entry = (ClassCacheEntry<T>) ClassCache.getInstance().loadClass(instance.getClass(), mapper);
        return entry.getList(instance, false, false);
    }

    /**
     * Given an instance of a class (of any type), convert its properties to a map, properties names will use as the
     * key and properties values will be the values.
     *
     * @param instance The instance of a class (of any type).
     * @return the properties {@link Map} of the given instance.
     */
    @SuppressWarnings("unchecked")
    public <T> Map<String, Object> convertToMap(@NotNull T instance) {
        ClassCacheEntry<T> entry = (ClassCacheEntry<T>) ClassCache.getInstance().loadClass(instance.getClass(), mapper);
        return entry.getMap(instance, false);
    }

    private Key createKey(ClassCacheEntry<?> entry, DeferredObjectLoader.DeferredObject deferredObject) {
        if (deferredObject.isDigest()) {
            return new Key(entry.getNamespace(), (byte[]) deferredObject.getKey(), entry.getSetName(), null);
        } else {
            return new Key(entry.getNamespace(), entry.getSetName(), Value.get(entry.translateKeyToAerospikeKey(deferredObject.getKey())));
        }
    }

    /**
     * If an object refers to other objects (eg A has a list of B via references), then reading the object will populate the
     * ids. If configured to do so, these objects can be loaded via a batch load and populated back into the references which
     * contain them. This method performs this batch load, translating the records to objects and mapping them back to the
     * references.
     * <p/>
     * These loaded child objects can themselves have other references to other objects, so we iterate through this until
     * the list of deferred objects is empty. The deferred objects are stored in a <pre>ThreadLocalData<pre> list, so are thread safe
     * @param parentEntity - the ClassCacheEntry of the parent entity. This is used to get the batch policy to use.
     */
    @SuppressWarnings("unchecked")
    public void resolveDependencies(ClassCacheEntry<?> parentEntity) {
        List<DeferredObjectLoader.DeferredObjectSetter> deferredObjects = DeferredObjectLoader.getAndClear();

        if (deferredObjects.isEmpty()) {
            return;
        }

        BatchPolicy batchPolicy = parentEntity == null ? aerospikeClient.getBatchPolicyDefault() : parentEntity.getBatchPolicy();
        BatchPolicy batchPolicyClone = new BatchPolicy(batchPolicy);

        while (!deferredObjects.isEmpty()) {
            List<Key> keyList = new ArrayList<>();
            List<ClassCacheEntry<?>> classCacheEntryList = new ArrayList<>();

            // Resolve any objects which have been seen before
            for (Iterator<DeferredObjectSetter> iterator = deferredObjects.iterator(); iterator.hasNext(); ) {
                DeferredObjectSetter thisObjectSetter = iterator.next();
                DeferredObjectLoader.DeferredObject deferredObject = thisObjectSetter.getObject();
                Class<?> clazz = deferredObject.getType();
                ClassCacheEntry<?> entry = MapperUtils.getEntryAndValidateNamespace(clazz, mapper);

                Key aKey = createKey(entry, deferredObject);
                Object result = LoadedObjectResolver.get(aKey);
                if (result != null) {
                    thisObjectSetter.getSetter().setValue(result);
                    iterator.remove();
                } else {
                    keyList.add(aKey);
                    classCacheEntryList.add(entry);
                }
            }

            int size = keyList.size();
            if (size > 0) {
                Key[] keys = keyList.toArray(new Key[0]);

                // Load the data
                if (keys.length <= 2) {
                    // Just single-thread these keys for speed
//                    batchPolicyClone.maxConcurrentThreads = 1;
                } else {
//                    batchPolicyClone.maxConcurrentThreads = batchPolicy.maxConcurrentThreads;
                }
                Record[] records = aerospikeClient.get(batchPolicyClone, keys);

                for (int i = 0; i < size; i++) {
                    DeferredObjectLoader.DeferredObjectSetter thisObjectSetter = deferredObjects.get(i);
                    try {
                        ThreadLocalKeySaver.save(keys[i]);
                        Object obj = convertToObject(
                                (Class<Object>) thisObjectSetter.getObject().getType(),
                                keys[i],
                                records[i],
                                (ClassCacheEntry<Object>) classCacheEntryList.get(i),
                                false);
                        Object result = records[i] == null ? null : obj;
                        thisObjectSetter.getSetter().setValue(result);
                    } finally {
                        ThreadLocalKeySaver.clear();
                    }
                }
            }
            deferredObjects = DeferredObjectLoader.getAndClear();
        }
    }
}
