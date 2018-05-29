package ta.nemahuta.neo4j.config;

import com.google.common.collect.ImmutableMap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.neo4j.driver.v1.AuthToken;
import org.neo4j.driver.v1.Config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A configuration for {@link ta.nemahuta.neo4j.structure.Neo4JGraph}.
 *
 * @author Christian Heike (christian.heike@icloud.com)
 */
@Builder
@ToString
@EqualsAndHashCode
@Slf4j
public class Neo4JConfiguration {

    private static final Map<Class<?>, Class<?>> BOXING_DEFINITION = ImmutableMap.<Class<?>, Class<?>>builder()
            .put(boolean.class, Boolean.class)
            .put(byte.class, Byte.class)
            .put(char.class, Character.class)
            .put(short.class, Short.class)
            .put(double.class, double.class)
            .put(float.class, float.class)
            .put(int.class, Integer.class)
            .put(long.class, Long.class)
            .build();

    /**
     * the host name to be used for connection
     */
    @NonNull
    @Getter(onMethod = @__(@Nonnull))
    @ConfigurationKey
    private final String hostname;

    /**
     * the port to be used for connection
     */
    @Getter
    @ConfigurationKey
    private final int port;

    /**
     * the authentication token to be used
     */
    @NonNull
    @Getter(onMethod = @__(@Nonnull))
    @ConfigurationKey
    private final AuthToken authToken;

    /**
     * the optional graph name to be used to partition the graph
     */
    @Getter(onMethod = @__(@Nullable))
    @ConfigurationKey
    private final String graphName;

    @Getter(onMethod = @__(@Nullable))
    @ConfigurationKey
    private final Consumer<Config.ConfigBuilder> additionConfiguration;

    @Nonnull
    public Configuration toApacheConfiguration() {
        final Configuration result = new BaseConfiguration();
        configurationFields()
                .forEach(f -> addField(result, f));

        return result;
    }

    @SneakyThrows
    private void addField(final Configuration result, final Field f) {
        final String key = !StringUtils.isEmpty(f.getAnnotation(ConfigurationKey.class).value()) ?
                f.getAnnotation(ConfigurationKey.class).value() :
                f.getName();
        result.addProperty(key, f.get(Neo4JConfiguration.this));
    }

    @Nonnull
    private static Stream<Field> configurationFields() {
        return Stream.of(Neo4JConfiguration.class.getDeclaredFields())
                .filter(f -> Objects.nonNull(f.getAnnotation(ConfigurationKey.class)));
    }

    @Nonnull
    @SneakyThrows
    public static Neo4JConfiguration fromApacheConfiguration(@Nonnull final Configuration configuration) {
        final Neo4JConfigurationBuilder builder = builder();
        configurationFields().forEach(f -> invokeBuilderMethod(configuration, builder, f));
        return builder.build();
    }

    @SneakyThrows
    private static void invokeBuilderMethod(final @Nonnull Configuration configuration, final Neo4JConfigurationBuilder builder, final Field f) {
        final Object value = configuration.getProperty(f.getName());
        if (value == null) {
            return;
        }
        final Method builderMethod = findBuilderMethod(f.getName(), value.getClass());
        builderMethod.invoke(builder, value);
    }

    @Nonnull
    @SneakyThrows
    private static Method findBuilderMethod(@Nonnull final String name,
                                            @Nonnull final Class<?> paramClass) {

        return Stream.of(Neo4JConfigurationBuilder.class.getDeclaredMethods())
                .filter(m -> name.equals(m.getName()))
                .filter(m -> m.getParameterTypes().length == 1 && canCoerce(paramClass, m.getParameterTypes()[0]))
                .findAny()
                .orElseThrow(() -> new NoSuchMethodException("Could not find method '" + name + "' which accepts " + paramClass.getName()));
    }

    private static boolean canCoerce(@Nonnull final Class<?> fromClass,
                                     @Nonnull final Class<?> toClass) {
        return BOXING_DEFINITION.getOrDefault(toClass, toClass).isAssignableFrom(BOXING_DEFINITION.getOrDefault(fromClass, fromClass));
    }

}
