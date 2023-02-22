///usr/bin/env java --enable-preview --source 19 --add-modules java.logging,jdk.httpserver "$0" "$@"; exit $?
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpResponse.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.logging.Formatter;

class ServeDoc {

    private static final Logger LOG = Logger.getLogger(ServeDoc.class.getName());

    public static void main(String[] args) throws IOException {
        var arguments = Args.parse(args);
        var artifactResolver = new ArtifactResolver(
                Path.of(System.getProperty("user.home"), ".m2", "repository"),
                new ArrayList<>(List.of(URI.create("https://repo1.maven.org/maven2"))));

        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s %5$s\u001b[31m%6$s\u001b[0m%n");

        var path = artifactResolver.resolve(arguments.artifact());
        var server = HttpServer.create(new InetSocketAddress(arguments.port()), 5);
        var app = new ServeDoc(path);
        var exec = Executors.newVirtualThreadPerTaskExecutor();

        server.createContext("/", app::handle);
        server.setExecutor(exec);

        LOG.info("Serving javadoc for \u001B[92m" + arguments.artifact() + "\u001B[0m on " + arguments.port());
        server.start();
    }

    private FileSystem fs;
    private Instant instantiatedAt;

    ServeDoc(Path path) throws IOException {
        this.fs = FileSystems.newFileSystem(path);
        instantiatedAt = Instant.now();
    }

    public void handle(HttpExchange exchange) {
        var uri = exchange.getRequestURI();
        var t0 = System.currentTimeMillis();

        try {
            switch (uri.getPath()) {
                case "/":
                    uri = URI.create("/index.html");
                    break;
                case "/favicon.ico":
                    notFound(exchange);
                    return;
                default:
                    break;
            }

            var path = fs.getPath(uri.toString());
            ZonedDateTime lastModifiedTime;
            byte[] bytes;
            long t1;

            if (Files.exists(path)) {
                lastModifiedTime = Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.of("GMT"));
                t1 = System.currentTimeMillis();
                bytes = Files.readAllBytes(path);
                LOG.fine(() -> "Read " + bytes.length + " bytes in " + (System.currentTimeMillis() - t1) + " ms");
            } else {
                notFound(exchange);
                return;
            }

            try (
                var os = exchange.getResponseBody();
            ) {
                exchange.getResponseHeaders().add("Last-Modified", DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz").format(lastModifiedTime));
                exchange.sendResponseHeaders(200, bytes.length);
                t0 = System.currentTimeMillis();
                os.write(bytes);
                LOG.fine(() -> "Wrote " + bytes.length + " bytes in " + (System.currentTimeMillis() - t1) + " ms");
            }
        } catch (Exception e) {
            LOG.warning("Failed to serve " + uri);
            LOG.log(Level.FINE, uri.toString(), e);
        } finally {
            LOG.fine(uri + " " + exchange.getResponseCode() + " " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    private void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, 0l);
        exchange.getResponseBody().close();
    }
}

record Args(int port, Artifact artifact) {
    public static Args parse(String[] args) {
        var port = 8000;
        Artifact artifact = null;

        for (var i = 0; i < args.length; i++) {
            var arg = args[i];
            switch (arg) {
                case "-p", "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                default:
                    if (artifact == null) {
                        var parts = arg.split(":");
                        if (parts.length != 3) {
                            System.err.println("Please specify version for " + parts[0] + ":" + parts[1]);
                            System.exit(1);
                        }
                        artifact = new Artifact(parts[0], parts[1], parts[2], "javadoc");
                    }
                    break;
            }
        }

        if (artifact == null) {
            throw new IllegalArgumentException("Artifact not specified");
        }

        return new Args(port, artifact);
    }
}

record Artifact(String groupId, String name, String version, String classifier) {

    public Path toPath() {
        var filename = name + "-" + version + ((classifier == null || classifier.isBlank()) ? "" : ("-" + classifier)) + ".jar";
        return Path.of(groupId.replaceAll("\\.", "/"), name, version, filename);
    }

    public URI toUri(URI repository) {
        var remotePath = repository.getPath();
        var uri = repository.resolve(Path.of(remotePath).resolve(toPath()).toString());
        return uri;
    }

    @Override
    public String toString() {
        return groupId + ":" + name + ":" + version;
    }

}

class ArtifactResolver {
    private static final Logger LOG = Logger.getLogger(ArtifactResolver.class.getName());
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Path localRepo;
    private ArrayList<URI> remoteRepos;

    public ArtifactResolver(Path localRepo, ArrayList<URI> remoteRepos) {
        this.localRepo = localRepo;
        this.remoteRepos = remoteRepos;
    }

    public Path resolve(Artifact artifact) {
        var path = artifact.toPath();
        var localPath = localRepo.resolve(path);
        if (!Files.exists(localPath)) {
            download(artifact, localPath);
        }

        return localPath;
    }

    void download(Artifact artifact, Path output) {
        for (var repoUri : remoteRepos) {
            var uri = artifact.toUri(repoUri);
            LOG.info("Downloading from " + uri);
            var req = HttpRequest.newBuilder(uri).GET().build();
            try {
                var resp = httpClient.send(req, BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) {
                    if (resp.statusCode() == 404) {
                        LOG.fine(() -> artifact + " not found at " + repoUri);
                    } else {
                        LOG.warning(() -> "The server at " + repoUri
                                        + " returned HTTP " + resp.statusCode()
                                        + " when fetching javadoc for " + artifact);
                    }

                    continue;
                }

                Files.createDirectories(output.getParent());

                try (
                    var is = new ByteArrayInputStream(resp.body());
                    var os = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
                ) {
                    is.transferTo(os);
                }

                LOG.info("Saved to " + output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
