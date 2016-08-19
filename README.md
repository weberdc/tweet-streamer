# Listen to Twitter's Streaming API

Author: **Derek Weber** (with many thanks to [http://twitter4j.org]() examples)

Last updated: **2016-08-18**

Simple app to listen to Twitter's streaming API, given one or more filtering
terms.

Requirements:
 + Java Development Kit 1.8
 + [twitter4j-core](http://twitter4j.org) (Apache 2.0 licence)
   + depends on [JSON](http://json.org) ([JSON licence](http://www.json.org/license.html))
 + [FasterXML](http://wiki.fasterxml.com/JacksonHome) (Apache 2.0 licence)
 + [jcommander](http://jcommander.org) (Apache 2.0 licence)
 + [httpcomponents](https://hc.apache.org/) (Apache 2.0 licence)

Built with [Gradle 3.0](http://gradle.org).

## To Build

The Gradle wrapper has been included, so it's not necessary for you to have Gradle
installed - it will install itself as part of the build process. All that is required is
the Java Development Kit.

By running

`$ ./gradlew installDist` or `$ gradlew.bat installDist`

you will create an installable copy of the app in `PROJECT_ROOT/build/tweet-streamer`.

Use the target `distZip` to make a distribution in `PROJECT_ROOT/build/distributions`
or the target `timestampedDistZip` to add a timestamp to the distribution archive filename.

To also include your own local `twitter.properties` file with your Twitter credentials,
use the target `privilegedDistZip` to make a special distribution in
`PROJECT_ROOT/build/distributions` that includes the current credentials.


## Configuration

Twitter OAuth credentials must be available in a properties file based on the
provided `twitter.properties-template` in the project's root directory. Copy the
template file to a properties file (the default is `twitter.properties` in the same
directory), and edit it with your Twitter app credentials. For further information see
[http://twitter4j.org/en/configuration.html]().

If running the app behind a proxy or filewall, copy the `proxy.properties-template`
file to a file named `proxy.properties` and set the properties inside to your proxy
credentials. If you feel uncomfortable putting your proxy password in the file, leave
the password-related ones commented and the app will ask for the password.

## Usage
If you've just downloaded the binary distribution, do this from within the unzipped
archive (i.e. in the `tweet-streamer` directory). Otherwise, if you've just built
the app from source, do this from within `PROJECT_ROOT/build/install/tweet-streamer`:
<pre>
Usage: bin/get-twitter-user[.bat] [options]
  Options:
    -c, --credentials
       Properties file with Twitter OAuth credentials
       Default: ./twitter.properties
    -f, --filter-level
       Filter level (options: none, low, medium)
       Default: none
    -g, --geo
       Geo-boxes as two lat/longs expressed as four doubles separated by spaces
       Default: []
    -h, --help
       Print usage instructions
       Default: false
    -i, --include-media
       Include media from tweets
       Default: false
    -l, --language
       Specify Tweet language (BCP 47)
       Default: [en]
    -o, --output
       Root directory to which to write output
       Default: ./output
    -q, --queue-size
       Size of processing queue (in tweets)
       Default: 1024
    -s, --screen-name
       Screen name of a user to follow
       Default: []
    -t, --term
       Filter term
       Default: []
    -u, --user-id
       ID of a user to follow
       Default: []
    -debug
       Debug mode
       Default: false
</pre>

Run the app with your desired configuration, e.g. `weberdc`, `#auspol`, ...:
<pre>
prompt> bin/tweet-streamer --screen-name weberdc -t '#auspol' -debug
</pre>

This will create a directory `output/<timestamp>` and create/download the following:

 + `info.json` includes the configuration specified (a map of parameters)
 + matching tweets, one per line, in a file in `output/<timestamp>/tweets/`

Attempts have been made to account for Twitter's rate limits, so at times the
app will pause, waiting until the rate limit has refreshed. It reports how long
it will wait when it does have to pause.
