package ta.nemahuta.neo4j;

import com.google.common.collect.ImmutableList;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.IoCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.v1.AuthTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlunit.matchers.CompareMatcher;
import ta.nemahuta.neo4j.config.Neo4JConfiguration;
import ta.nemahuta.neo4j.structure.Neo4JGraphFactory;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleCreateAndLoadTest {

    private static final UUID uuid = UUID.randomUUID();
    private static final Logger log = LoggerFactory.getLogger(SimpleCreateAndLoadTest.class);

    private static Neo4JGraphFactory graphFactory;
    private final CountDownLatch latch = new CountDownLatch(1);

    static {
        try {
            graphFactory = createFactory();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    SimpleCreateAndLoadTest() throws Exception {
    }

    @Nonnull
    static private Neo4JGraphFactory createFactory() throws Exception {
        closeFactory();
        return graphFactory = new Neo4JGraphFactory(
                Neo4JConfiguration.builder()
                        .graphName(uuid.toString())
                        .hostname("localhost")
                        .port(7687)
                        .authToken(AuthTokens.basic("neo4j", "neo4j123")).build()
        );
    }

    @AfterAll
    static void closeFactory() throws Exception {
        if (graphFactory != null) {
            graphFactory.close();
            graphFactory = null;
        }
    }

    @Test
    void checkCreateAndReadSmallGraph() throws Exception {
        checkGraph("/graph1-example.xml", 6, 6);
        withGraph(graph -> {
            final Optional<Vertex> joshOpt = ImmutableList.copyOf(graph.vertices()).stream()
                    .filter(v -> Objects.equals(v.property("name").value(), "josh")).findAny();
            assertTrue(joshOpt.isPresent(), "Josh is not present");
            final Vertex josh = joshOpt.get();
            assertTrue(josh.edges(Direction.OUT).hasNext(), "No out edges for josh");
            josh.remove();
            assertTrue(ImmutableList.copyOf(graph.vertices()).stream()
                    .noneMatch(v -> Objects.equals(v.property("name").value(), "josh")));
            graph.tx().commit();
        });
        withGraph(graph -> {
            assertTrue(ImmutableList.copyOf(graph.vertices()).stream()
                    .noneMatch(v -> Objects.equals(v.property("name").value(), "josh")));
        });
    }

    @Test
    void parallelTest1() throws Exception {
        parallelStream(1000, "/graph1-example.xml");
    }

    @Test
    void parallelTest2() throws Exception {
        parallelStream(10, "/graph2-example.xml");
    }

    private void parallelStream(final int max, final String source) throws InterruptedException {
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Future<Boolean>> futures = executor.invokeAll(
                Stream.<Callable<Boolean>>generate(() -> () -> {
                    latch.countDown();
                    try {
                        streamGraph(source);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return true;
                }).limit(max).collect(Collectors.toList())
        );
        executor.shutdown();
        assertTrue(futures.stream().allMatch(f -> {
            try {
                return f.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void checkCreateAndReadHugeGraph() throws Exception {
        checkGraph("/graph2-example.xml", 809, 8049);
    }

    private void checkGraph(final String source, final long vertexCount, final long edgeCount) throws Exception {
        streamGraph(source);
        compareGraph(source, vertexCount, edgeCount);
    }

    @AfterEach
    void clearGraph() throws Exception {
        if (graphFactory != null) {
            withGraph(graph -> {
                graph.vertices().forEachRemaining(Vertex::remove);
                graph.tx().commit();
            });
        }
    }

    private <T> T withGraph(final GraphFunction<T> callable) throws Exception {
        try (Graph graph = graphFactory.get()) {
            try (Transaction tx = graph.tx()) {
                return callable.apply(graph);
            }
        }
    }

    private void withGraph(final GraphConsumer consumer) throws Exception {
        withGraph(graph -> {
            consumer.consume(graph);
            return null;
        });
    }

    private void compareGraph(final String source, final long vertexCount, final long edgeCount) throws Exception {
        try (OutputStream os = new ByteArrayOutputStream()) {
            withGraph(graph -> {
                assertEquals(vertexCount, ImmutableList.copyOf(graph.vertices()).size());
                assertEquals(edgeCount, ImmutableList.copyOf(graph.edges()).size());
                graph.io(IoCore.graphml()).writer().create().writeGraph(os, graph);
                graph.tx().rollback();
            });
            log.debug("\n", new String(((ByteArrayOutputStream) os).toByteArray()));
            try (Reader expected = new InputStreamReader(getClass().getResourceAsStream(source))) {
                try (Reader actual = new InputStreamReader(new ByteArrayInputStream(((ByteArrayOutputStream) os).toByteArray()))) {
                    assertThat(expected, CompareMatcher.isIdenticalTo(actual));
                }
            }
        }
    }

    private void streamGraph(@Nonnull final String source) throws Exception {
        withGraph(graph -> {
            try (InputStream is = getClass().getResourceAsStream(source)) {
                graph.io(IoCore.graphml()).reader().create().readGraph(is, graph);
            }
        });
    }

    @FunctionalInterface
    public interface GraphFunction<T> {
        T apply(Graph graph) throws Exception;
    }

    @FunctionalInterface
    public interface GraphConsumer {
        void consume(Graph graph) throws Exception;
    }

}
