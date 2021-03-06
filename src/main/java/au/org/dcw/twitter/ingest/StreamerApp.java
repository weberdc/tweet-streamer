/*
 * Copyright 2016 Derek Weber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.org.dcw.twitter.ingest;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import twitter4j.ExtendedMediaEntity;
import twitter4j.FilterQuery;
import twitter4j.MediaEntity;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterObjectFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.URLEntity;
import twitter4j.UserMentionEntity;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * Application that streams tweets from the Twitter Streaming API, given query
 * terms, user IDs and/or geo-boxes.
 * <p>
 *
 * @see <a href=
 *      "https://github.com/yusuke/twitter4j/blob/master/twitter4j-examples/src/main/java/twitter4j/examples/json/SaveRawJSON.java">SaveRawJSON.java</a>
 * @see <a href=
 *      "https://github.com/yusuke/twitter4j/blob/master/twitter4j-examples/src/main/java/twitter4j/examples/stream/PrintSampleStream.java">Twitter4j
 *      Streaming Exapmle</a>
 * @see <a href=
 *      "https://dev.twitter.com/streaming/reference/post/statuses/filter">Twitter's
 *      <code>/statuses/filter</code> endpoint</a>
 */
public final class StreamerApp {

    static class Tweet {
        public final Status status;
        public final String rawJSON;

        public Tweet(final Status status) {
            this.status = status;
            this.rawJSON = TwitterObjectFactory.getRawJSON(status);
        }
    }

    public class GeoboxConverter implements IStringConverter<double[]> {
        @Override
        public double[] convert(String value) {
            double[] geobox = new double[4];
            int i = 0;
            for (String longStr : value.split(",")) {
                geobox[i++] = Long.parseLong(longStr);
            }
            return geobox;
        }
    }

    public class FilterLevelConverter implements IStringConverter<String> {
        @Override
        public String convert(String value) {
            if (value == null || value.trim().isEmpty()) {
              System.err.println("Filter level \"" + value +
                                 "\" not recognised. Resorting to \"none\".");
            } else if (value.equalsIgnoreCase("low")) {
                return "low";
            } else if (value.equalsIgnoreCase("medium")) {
                return "medium";
            } else {
                System.err.println("Filter level \"" + value +
                                   "\" not recognised. Resorting to \"none\".");
            }
            return "none";
        }
    }

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DT_LOG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DT_HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final int MAX_TRACK_TERMS = 400;
    private static final int FOLLOW_LIMIT = 5000;
    private static final int GEOBOX_LIMIT = 25;

    private final String USER_AGENT = "Mozilla/5.0";
    @Parameter(names = { "-t", "--term" }, description = "Filter term", variableArity = true)
    private final List<String> filterTerms = new ArrayList<>();

    @Parameter(names = { "-u", "--user-id" }, description = "ID of a user to follow")
    private final List<Long> userIds = new ArrayList<>();

    @Parameter(names = { "-s", "--screen-name" }, description = "Screen name of a user to follow")
    private final List<String> screenNames = new ArrayList<>();

    @Parameter(names = { "-g", "--geo" },
               description = "Geo-boxes as two lat/longs expressed as four doubles separated by spaces",
               converter = GeoboxConverter.class)
    private final List<double[]> geoboxen = new ArrayList<>();

    @Parameter(names = { "-l", "--language" }, description = "Specify Tweet language (BCP 47)")
    private final List<String> languages = new ArrayList<>(Arrays.asList("en"));

    @Parameter(names = { "-f", "--filter-level" },
               description = "Filter level (options: none, low, medium)",
               converter = FilterLevelConverter.class)
    private String filterLevel = "none";

    @Parameter(names = { "-o", "--output" },
               description = "Root directory to which to write output")
    private String rootOutputDir = "./output";

    @Parameter(names = { "-c", "--credentials" },
               description = "Properties file with Twitter OAuth credentials")
    private String credentialsFile = "./twitter.properties";

    @Parameter(names = { "-q", "--queue-size" },
               description = "Size of processing queue (in tweets)")
    private Integer queueSize = 1024;

    @Parameter(names = "-debug", description = "Debug mode")
    private boolean debug = false;

    @Parameter(names = { "-i", "--include-media" }, description = "Include media from tweets")
    private boolean includeMedia = false;

    @Parameter(names = { "-h", "--help" }, description = "Print usage instructions")
    private boolean printUsage = false;

    /**
     * Usage:
     *
     * <pre>
     * Usage: <main class> [options]
     *   Options:
     *     -c, --credentials
     *        Properties file with Twitter OAuth credentials
     *        Default: ./twitter.properties
     *     -f, --filter-level
     *        Filter level (options none, low, medium)
     *        Default: none
     *     -g, --geo
     *        Geo-boxes as two lat/longs expressed as four doubles separated by spaces
     *        Default: []
     *     -h, --help
     *        Print usage instructions
     *        Default: false
     *     -i, --include-media
     *        Include media from tweets
     *        Default: false
     *     -l, --language
     *        Specify Tweet language (BCP 47)
     *        Default: [en]
     *     -o, --output
     *        Root directory to which to write output
     *        Default: ./output
     *     -q, --queue-size
     *        Size of processing queue (in tweets)
     *        Default: 2014
     *     -s, --screen-name
     *        Screen name of a user to follow
     *        Default: none
     *     -t, --term
     *        Filter term
     *        Default: none
     *     -u, --user-id
     *        ID of a user to follow
     *        Default: none
     *     -debug
     *        Debug mode
     *        Default: false
     * </pre>
     *
     * @param args Command line arguments
     */
    public static void main(final String[] args) throws IOException {
        final StreamerApp theApp = new StreamerApp();

        final JCommander argsParser = new JCommander(theApp, args);

        if (theApp.printUsage || !checkFieldsOf(theApp)) {
            final StringBuilder sb = new StringBuilder();
            argsParser.usage(sb);
            System.out.println(sb.toString());
            System.exit(-1);
        }

        theApp.run();
    }

    /**
     * Checks to see if command line argument constraints have been met.
     *
     * @param app the app the fields of which to check
     */
    private static boolean checkFieldsOf(final StreamerApp app) {
        if (app.filterTerms.isEmpty() && app.userIds.isEmpty() && app.screenNames.isEmpty() &&
            app.geoboxen.isEmpty()) {
            System.out.println("Error: one or more filter terms, user IDs, screen names or " +
                               "geo-boxes must be supplied");
            return false;
        }
        return true;
    }

    private TweetListener tweetListener;
    private TweetWriter tweetWriter;
    private CloseableHttpClient httpClient;

    /**
     * Runs the app, collecting tweets from Twitter's streaming API, using the
     * provided query parameters, writing them out as raw JSON to
     * {@link #rootOutputDir}/&lt;<i>timestamp</i>&gt;.
     *
     * @throws IOException if there's a problem talking to Twitter or writing
     *         JSON out.
     */
    public void run() throws IOException {
        this.reportConfiguration();

        this.httpClient =
            HttpClientBuilder.create().setSSLContext(this.setupSSLCertificates()).build();

        Thread producer = null;
        Thread consumer = null;

        final Configuration config = this.buildTwitterConfiguration();
        final FilterQuery filter = this.createFilterQuery();
        final String outputDir = this.rootOutputDir + "/" + StreamerApp.nowStr(TIMESTAMP);

        try {
            final BlockingQueue<Tweet> tweetQueue = new ArrayBlockingQueue<>(1024);
            this.tweetListener = new TweetListener(tweetQueue, config, filter);
            this.tweetWriter = new TweetWriter(tweetQueue, outputDir);

            producer = new Thread(this.tweetListener, "Tweet stream listener");
            consumer = new Thread(this.tweetWriter, "Tweet writer");
            Thread quitListener =
                new Thread(new QuitListener(consumer, this.tweetListener, this.tweetWriter),
                           "Quit keystroke listener");

            producer.start();
            consumer.start();
            quitListener.start();

            producer.join();
            consumer.join();
            quitListener.join();

        } catch (final RuntimeException | InterruptedException e) {
            System.err.println("Something barfed: " + e.getMessage());
            e.printStackTrace();
        }

        // attempt to catch Ctrl-C
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("StreamerApp shutting down...");
                // report findings, run time, tweets collected, etc
                StreamerApp.this.tweetListener.close();
                // StreamerApp.this.tweetWriter.close();
                try {
                    StreamerApp.this.httpClient.close();
                } catch (IOException e) {
                    System.err.println("Failed to close http client.");
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * A forgiving SSL setup is required for talking to https://tweeterid.com/
     * to do reverse-lookups of user IDs to follow.
     * <p>
     *
     * @see <a href=
     *      "http://stackoverflow.com/questions/1828775/how-to-handle-invalid-ssl-certificates-with-apache-httpclient">Props
     *      to this post for how to do this.</a>
     * @return A forgiving SSL context
     */
    private SSLContext setupSSLCertificates() {
        // configure the SSLContext with a TrustManager
        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[0], new TrustManager[] { new DefaultTrustManager() },
                     new SecureRandom());
            SSLContext.setDefault(ctx);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            System.err.println("Error setting SSL up: " + e.getMessage());
            e.printStackTrace();
        }
        return ctx;
    }

    /**
     * Forgiving trust manager for talking to https://...
     */
    private static class DefaultTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] arg0, String arg1)
            throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] arg0, String arg1)
            throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }

    private FilterQuery createFilterQuery() {
        String[] termsArray = this.limit(this.toStringArray(this.filterTerms), MAX_TRACK_TERMS);

        List<Long> idsToFollow = this.screenNamesToIDs();
        idsToFollow.addAll(this.userIds);
        long[] idsArray = this.limit(this.toPrimitiveArray(idsToFollow), FOLLOW_LIMIT);

        String[] langsArray = this.toStringArray(this.languages);
        double[][] geoboxenArray = this.limit(this.toDouble2DArray(this.geoboxen), GEOBOX_LIMIT);

        FilterQuery filter = new FilterQuery();
        if (termsArray.length > 0) {
            filter.track(termsArray);
        } else if (idsArray.length > 0) {
            filter.follow(idsArray);
        } else if (langsArray.length > 0) {
            filter.language(langsArray);
        } else if (geoboxenArray.length > 0) {
            filter.locations(geoboxenArray);
        }

        filter.filterLevel(this.filterLevel);

        return filter;
    }

    private List<Long> screenNamesToIDs() {
        List<Long> ids = new ArrayList<>();
        for (String screenName : this.screenNames) {
            try {
                HttpPost post = new HttpPost("https://tweeterid.com/ajax.php");
                post.setHeader("User-Agent", this.USER_AGENT);
                List<NameValuePair> formparams = new ArrayList<>();
                formparams.add(new BasicNameValuePair("input", screenName));
                post.setEntity(new UrlEncodedFormEntity(formparams, Consts.UTF_8));

                CloseableHttpResponse response = this.httpClient.execute(post);
                try {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        long len = entity.getContentLength();
                        if (len != -1 && len < 2048) {
                            String string = EntityUtils.toString(entity);
                            long id = Long.parseLong(string);
                            System.out.println("Resolved @" + screenName + " -> " + id);
                            ids.add(id);
                        } else {
                            System.err.println("Error converting screen name to ID: Error in response");
                            EntityUtils.consume(entity);
                            System.err.println("=> " + EntityUtils.toString(entity));
                        }
                    }
                } finally {
                    response.close();
                }
            } catch (IOException e) {
                System.err.println("Error converting screen name to ID: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return ids;
    }

    private double[][] limit(double[][] array, int maxLength) {
        if (array.length > maxLength) {
            double[][] limited = new double[maxLength][];
            System.arraycopy(array, 0, limited, 0, maxLength);
            return limited;
        }
        return array;
    }

    private long[] limit(long[] array, int maxLength) {
        if (array.length > maxLength) {
            long[] limited = new long[maxLength];
            System.arraycopy(array, 0, limited, 0, maxLength);
            return limited;
        }
        return array;
    }

    private String[] limit(String[] array, int maxLength) {
        if (array.length > maxLength) {
            String[] limited = new String[maxLength];
            System.arraycopy(array, 0, limited, 0, maxLength);
            return limited;
        }
        return array;
    }

    private double[][] toDouble2DArray(List<double[]> list) {
        return list.toArray(new double[list.size()][]);
    }

    private String[] toStringArray(List<String> list) {
        return list.toArray(new String[list.size()]);
    }

    private long[] toPrimitiveArray(List<Long> listOfLongs) {
        if (listOfLongs.isEmpty()) {
            return new long[]{};
        }
        long[] longArray = new long[listOfLongs.size()];
        for (int i = 0; i < listOfLongs.size(); i++) {
            longArray[i] = listOfLongs.get(i).longValue();
        }
        return longArray;
    }

    protected static String nowStr(final DateTimeFormatter formatter) {
        return LocalDateTime.now().format(formatter);
    }

    public class TweetListener implements Runnable, Closeable, StatusListener {
        private final BlockingQueue<Tweet> queue;
        private final FilterQuery filter;
        private final TwitterStream twitterStream;

        public TweetListener(final BlockingQueue<Tweet> queue, final Configuration config,
                             final FilterQuery filter) {
            this.queue = queue;
            this.filter = filter;
            this.twitterStream = new TwitterStreamFactory(config).getInstance();
        }

        @Override
        public void run() {
            this.twitterStream.addListener(this);
            this.twitterStream.filter(this.filter);
        }

        @Override
        public void close() {
            System.out.println("Ceasing to listen to Twitter...");
            if (this.twitterStream != null) {
                this.twitterStream.shutdown();
            }
        }

        @Override
        public void onStatus(final Status status) {
            if (!this.queue.offer(new Tweet(status))) {
                System.out.printf("QUEUE FULL - dropping status: [%d]\n@%s: %s", status.getId(),
                                  status.getUser().getScreenName(), status.getText());
            }
        }

        @Override
        public void onDeletionNotice(final StatusDeletionNotice statusDeletionNotice) {
            System.out.println("Got a status deletion notice id:"
                    + statusDeletionNotice.getStatusId());
        }

        @Override
        public void onTrackLimitationNotice(final int numberOfLimitedStatuses) {
            System.err.println("Got track limitation notice:" + numberOfLimitedStatuses);
            System.err.println("You filter is matching too many tweets. Failure may occur.");
        }

        @Override
        public void onScrubGeo(final long userId, final long upToStatusId) {
            System.out.println("Got scrub_geo event userId:" + userId + " upToStatusId:"
                    + upToStatusId);
        }

        @Override
        public void onStallWarning(final StallWarning warning) {
            System.err.println("Got stall warning:" + warning);
            System.err.println("Consider increasing the size of your processing queue "
                    + "with the --queue-size (or -q) option");
        }

        @Override
        public void onException(final Exception ex) {
            System.err.println("Twitter Streaming API reported an exception:");
            ex.printStackTrace();
        }
    }

    public class TweetWriter implements Runnable, Closeable {

        private final BlockingQueue<Tweet> queue;
        private boolean shuttingDown = false;
        private BufferedWriter writer = null;
        private final String outputDir;
        private Path currentFile;
        private final ObjectMapper json;

        public TweetWriter(final BlockingQueue<Tweet> queue, final String outputDir) {
            this.queue = queue;
            this.outputDir = outputDir;
            this.json = new ObjectMapper();
        }

        @Override
        public void close() {
            System.out.println("TweetWriter cleaning up files...");
            this.shuttingDown = true;
            if (this.writer != null) {
                try {
                    this.writer.flush();
                    this.writer.close();
                } catch (final IOException e) {
                    System.err.println("TweetWriter failed to clean up: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        /**
         * Runs the TweetWriter, reading Tweets off of the blocking queue and
         * adding them to the current tweet log file.
         */
        @Override
        public void run() {
            int tweetCount = 0;
            try {
                String mediaDir = this.outputDir + "/media";
                if (StreamerApp.this.includeMedia) {
                    new File(mediaDir).mkdirs();
                }
                while (!this.shuttingDown) {
                    try {
                        final Tweet tweet = this.queue.take();

                        this.writer = this.ensureLogFile(this.outputDir);

                        if (StreamerApp.this.debug) {
                            this.printTweet(tweet);
                        }

                        this.writer.write(tweet.rawJSON + "\n");
                        this.writer.flush();
                        tweetCount++;

                        if (tweetCount % 1000 == 0) {
                            System.out.println("[" + StreamerApp.nowStr(StreamerApp.DT_LOG) +
                                               "] Collected " + tweetCount + " tweets...");
                        }

                        if (StreamerApp.this.includeMedia) {
                            int fetched =
                                StreamerApp.this.fetchMedia(tweet.status.getId(),
                                                            collectAllURLs(tweet.status), mediaDir);
                            if (StreamerApp.this.debug) {
                                System.out.printf("Grabbed %d media...\n", fetched);
                            }
                        }

                    } catch (final InterruptedException e) {
                        this.shuttingDown = true;
                        Thread.currentThread().interrupt();
                        System.err.printf("Shutting down with %d unprocessed tweets\n",
                                          this.queue.size());
                    } catch (final IOException e) {
                        if (this.writer == null) {
                            System.err.println("Failed to create output file in " + this.outputDir
                                    + ": " + e.getMessage());
                        } else {
                            System.err.println("Failed to write to output file "
                                    + this.currentFile);
                        }
                    }
                }
            } finally {
                System.out.println("[" + StreamerApp.nowStr(StreamerApp.DT_LOG) +
                                   "] Wrapping up. Collected " + tweetCount + " tweets...");
                this.close();
            }
        }

        /**
         * Debug method to print the contents of a Tweet.
         *
         * @param tweet The Tweet of which to print the contents.
         */
        private void printTweet(final Tweet tweet) {
            final String screenName = tweet.status.getUser().getScreenName();
            final String retweetInfo = tweet.status.isRetweet()
                    ? " retweeting @" + tweet.status.getRetweetedStatus().getUser()
                                                    .getScreenName()
                    : "";
            System.out.println("---------------------");
            System.out.println(tweet.status.getCreatedAt());
            System.out.println("@" + screenName + (retweetInfo));
            System.out.println(tweet.status.getText());
            for (UserMentionEntity mention: tweet.status.getUserMentionEntities()) {
                System.out.println("- mentions @" + mention.getScreenName());
            }
            for (URLEntity url : tweet.status.getURLEntities()) {
                System.out.println("- url: " + url.getExpandedURL());
            }
            for (MediaEntity media : tweet.status.getMediaEntities()) {
                System.out.println("- media: " + media.getMediaURLHttps());
            }
            System.out.println("---------------------");
        }

        /**
         * Ensures the current log file has been created and returns a writer to
         * it to add new tweets.
         *
         * @param rootDir The directory into which all content is to be written.
         * @return A writer to the current tweet log file.
         * @throws IOException If an error occurs creating files.
         */
        private BufferedWriter ensureLogFile(final String rootDir) throws IOException {
            if (this.currentFile != null && Files.exists(this.currentFile)) {
                return this.writer;
            } else {
                final String tweetDir = this.ensureTweetsDir(rootDir);
                this.ensureInfoFile(rootDir);
                this.currentFile = this.createCurrentLogPath(tweetDir);
                return Files.newBufferedWriter(this.currentFile, StandardCharsets.UTF_8,
                                               StandardOpenOption.CREATE,
                                               StandardOpenOption.APPEND);
            }
        }

        /**
         * Creates the Path to the current log file to use, which may or may not
         * already exist.
         *
         * @param tweetDir The directory in which the log file should reside.
         * @return The Path to the current log file.
         */
        private Path createCurrentLogPath(final String tweetDir) {
            final String fn = tweetDir + "/stream-" + StreamerApp.nowStr(StreamerApp.DT_HOUR)
                    + ".json";
            return Paths.get(fn);
        }

        /**
         * Ensures the {@code info.json} file has been created directly under
         * the rootDir.
         *
         * @param rootDir The directory in which the info file resides.
         * @throws IOException If an error occurs writing the info file.
         * @throws JsonProcessingException If an error occurs converting the
         *         config map to JSON.
         */
        private void ensureInfoFile(final String rootDir)
            throws IOException, JsonProcessingException {
            final Path infoFile = Paths.get(rootDir + "/info.json");
            if (Files.notExists(infoFile, LinkOption.NOFOLLOW_LINKS)) {
                final BufferedWriter infoWriter = Files.newBufferedWriter(infoFile,
                                                                          StandardCharsets.UTF_8);
                infoWriter.write(this.json.writerWithDefaultPrettyPrinter()
                                          .writeValueAsString(StreamerApp.this.configAsMap()));
                infoWriter.flush();
                infoWriter.close();
            }
        }

        /**
         * Make sure the tweets directory under the {@code rootDir} has been
         * created.
         *
         * @param rootDir The parent to the tweets directory.
         * @return The path to the tweets directory.
         */
        private String ensureTweetsDir(final String rootDir) {
            final String tweetDir = rootDir + "/tweets";
            if (Files.notExists(Paths.get(tweetDir), LinkOption.NOFOLLOW_LINKS) &&
                !new File(tweetDir).mkdirs()) {
                throw new RuntimeException("Could not create dir " + tweetDir + " - failing out");
            }
            return tweetDir;
        }
    }

    /**
     * Listened to stdin for 'q' to quit the app neatly.
     */
    class QuitListener implements Runnable {
        private TweetWriter writer;
        private Thread writerThread;
        private TweetListener tweetStream;

        public QuitListener(Thread consumerThread, TweetListener tweetStream, TweetWriter tweetWriter) {
            this.writerThread = consumerThread;
            this.tweetStream = tweetStream;
            this.writer = tweetWriter;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                while (!in.readLine().toLowerCase().equals("q")) {
                    System.out.println("Type 'q' to quit.");
                }
                System.out.println("Quit command received. Shutting down. This may take up to a minute.");
            } catch (IOException e) {
                System.err.println("An error occurred waiting for a quit keystroke: " +
                                   e.getMessage());
            } finally {
                this.tweetStream.close();

                this.writer.shuttingDown = true;
                this.writerThread.interrupt();
            }
        }
    }

    /**
     * Creates a Map of configuration information for JSON serialisation.
     *
     * @return A non-null map of Strings to values.
     */
    protected Map<String, Object> configAsMap() {
        final TreeMap<String, Object> config = new TreeMap<>();
        config.put("filter", this.filterTerms);
        config.put("geo_boxes", this.geoboxen);
        config.put("screen_names", this.screenNames);
        config.put("languages", this.languages);
        config.put("user_ids", this.userIds);
        config.put("include_media", this.includeMedia);
        config.put("queue_size", this.queueSize);
        return config;
    }

    /**
     * An introductory report to stdout, highlighting the configuration to be
     * used.
     */
    private void reportConfiguration() {
        System.out.println("== BEGIN Configuration ==");
        System.out.println("credentials: " + this.credentialsFile);
        System.out.println("queue size: " + this.queueSize);
        System.out.println("filters:" + this.filterTerms);
        System.out.println("user ids: " + this.userIds);
        System.out.println("screen names: " + this.screenNames);
        System.out.println("geoboxes: " +
                           this.geoboxen.stream().map(Arrays::asList).collect(Collectors.toList()));
        System.out.println("languages: " + this.languages);
        System.out.println("media: " + this.includeMedia);
        System.out.println("debug: " + this.debug);
        System.out.println("== END Configuration ==");
    }

    /**
     * Builds the {@link Configuration} object with which to connect to Twitter,
     * including credentials and proxy information if it's specified.
     *
     * @return a Twitter4j {@link Configuration} object
     * @throws IOException if there's an error loading the application's
     *         {@link #credentialsFile}.
     */
    private Configuration buildTwitterConfiguration() throws IOException {
        // TODO find a better name than credentials, given it might contain
        // proxy info
        final Properties credentials = loadCredentials(this.credentialsFile);

        final ConfigurationBuilder conf = new ConfigurationBuilder();
        conf.setJSONStoreEnabled(true).setDebugEnabled(this.debug)
            .setOAuthConsumerKey(credentials.getProperty("oauth.consumerKey"))
            .setOAuthConsumerSecret(credentials.getProperty("oauth.consumerSecret"))
            .setOAuthAccessToken(credentials.getProperty("oauth.accessToken"))
            .setOAuthAccessTokenSecret(credentials.getProperty("oauth.accessTokenSecret"));

        final Properties proxies = loadProxyProperties();
        if (proxies.containsKey("http.proxyHost")) {
            conf.setHttpProxyHost(proxies.getProperty("http.proxyHost"))
                .setHttpProxyPort(Integer.parseInt(proxies.getProperty("http.proxyPort")))
                .setHttpProxyUser(proxies.getProperty("http.proxyUser"))
                .setHttpProxyPassword(proxies.getProperty("http.proxyPassword"));
        }

        return conf.build();
    }

    /**
     * Fetches as many of the {@code mediaUrls} as possible to {@code mediaDir}.
     *
     * @param mediaUrls the URLs of potential media files
     * @param mediaDir the directory to which to write the media files
     * @return the number of URLs that referred to media which were successfully
     *         downloaded
     */
    private int fetchMedia(final long tweetId, final Set<String> mediaUrls,
                           final String mediaDir) {
        int fetched = 0;
        int mediaCount = 1;
        for (String urlStr : mediaUrls) {
            urlStr = this.tweak(urlStr);
            System.out.printf("MEDIA URL? %d/%d FROM %s ...", mediaCount++, mediaUrls.size(),
                              urlStr);
            final String ext = urlStr.substring(urlStr.lastIndexOf('.') + 1);
            final String filename = tweetId + "-" + (mediaCount++) + "." + ext;
            try {
                final URL url = new URL(urlStr);
                final BufferedImage bi = ImageIO.read(url);
                ImageIO.write(bi, ext, new File(mediaDir + "/" + filename));
                fetched++;
                System.out.println(" SUCCESS");
            } catch (IllegalArgumentException | IOException e) {
                System.out.println(" FAIL(" + e.getMessage() + ") - Skipping");
            }
        }
        return fetched;
    }

    /**
     * Opportunity to modify known URLs to make it easier to access the media to
     * which they refer.
     *
     * @param urlStr the original URL string
     * @return a potentially modified URL string
     */
    private String tweak(String urlStr) {
        if (urlStr.matches("^https?\\:\\/\\/imgur.com\\/")) {
            // e.g. "https://imgur.com/gallery/vLPhaca" to
            // "https://i.imgur.com/vLPhaca.gif"
            final String imgId = urlStr.substring(urlStr.lastIndexOf("/") + 1);
            urlStr = "https://i.imgur.com/download/" + imgId;
        }
        return urlStr;
    }

    /**
     * Look for all URLs in the given Tweet in case they refer to media of some
     * kind, and add them to the {@code mediaURLs} map.
     *
     * @param tweet the Tweet to examine
     * @return a set of URL Strings
     */
    protected static Set<String> collectAllURLs(final Status tweet) {
        final Set<String> urls = collectMentionedURLs(tweet);

        if (tweet.getMediaEntities().length > 0) {
            for (MediaEntity entity : tweet.getMediaEntities()) {
                urls.add(entity.getMediaURLHttps());
            }
        }

        if (tweet.getExtendedMediaEntities().length > 0) {
            for (ExtendedMediaEntity entity : tweet.getExtendedMediaEntities()) {
                switch (entity.getType()) {
                case "video":
                    urls.add(entity.getVideoVariants()[0].getUrl());
                    break;
                default:
                    urls.add(entity.getMediaURLHttps());
                    break;
                }
            }
        }

        return urls;
    }

    /**
     * A convenience method to collect the URLs mentioned in {@code tweet} and
     * return them in a set.
     *
     * @param tweet a Tweet, perhaps containing URLs
     * @return the set of URL strings mentioned in the tweet
     */
    protected static Set<String> collectMentionedURLs(final Status tweet) {
        final Set<String> urls = new TreeSet<>();
        if (tweet.getURLEntities().length > 0) {
            for (final URLEntity entity : tweet.getURLEntities()) {
                urls.add(entity.getExpandedURL());
            }
        }
        return urls;
    }

    /**
     * Loads the given {@code credentialsFile} from disk.
     *
     * @param credentialsFile the properties file with the Twitter credentials
     *        in it
     * @return A {@link Properties} map with the contents of credentialsFile
     * @throws IOException if there's a problem reading the credentialsFile.
     */
    private static Properties loadCredentials(final String credentialsFile) throws IOException {
        final Properties properties = new Properties();
        properties.load(Files.newBufferedReader(Paths.get(credentialsFile)));
        return properties;
    }

    /**
     * Loads proxy properties from {@code ./proxy.properties} and, if a password
     * is not supplied, asks for it in the console.
     *
     * @return A Properties instance filled with proxy information.
     */
    private static Properties loadProxyProperties() {
        final Properties properties = new Properties();
        final String proxyFile = "./proxy.properties";
        if (new File(proxyFile).exists()) {
            boolean success = true;
            try (Reader fileReader = Files.newBufferedReader(Paths.get(proxyFile))) {
                properties.load(fileReader);
            } catch (final IOException e) {
                System.err.println("Attempted and failed to load " + proxyFile + ": "
                        + e.getMessage());
                success = false;
            }
            if (success && !properties.containsKey("http.proxyPassword")) {
                final char[] password = System.console()
                                              .readPassword("Please type in your proxy password: ");
                properties.setProperty("http.proxyPassword", new String(password));
                properties.setProperty("https.proxyPassword", new String(password));
            }
            properties.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
        }
        return properties;
    }
}
