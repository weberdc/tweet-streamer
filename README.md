# Listen to Twitter's Streaming API

Author: **Derek Weber** (with many thanks to [http://twitter4j.org]() examples)

Last updated: **2016-07-08**

Simple app to listen to Twitter's streaming API, given one or more filtering
terms.

Requirements:
 + Java Development Kit 1.8
 + [twitter4j-core](http://twitter4j.org) (Apache 2.0 licence)
   + depends on [JSON](http://json.org) ([JSON licence](http://www.json.org/license.html))
 + [FasterXML](http://wiki.fasterxml.com/JacksonHome) (Apache 2.0 licence)
 + [jcommander](http://jcommander.org) (Apache 2.0 licence)

Built with [Gradle 2.12](http://gradle.org).

## To Build

The Gradle wrapper has been included, so it's not necessary for you to have Gradle
installed - it will install itself as part of the build process. All that is required is
the Java Development Kit.

By running

`$ ./gradlew installDist` or `$ gradlew.bat installDist`

you will create an installable copy of the app in `PROJECT_ROOT/build/get-twitter-user`.

Use the target `distZip` to make a distribution in `PROJECT_ROOT/build/distributions`
or the target `timestampedDistZip` to add a timestamp to the distribution archive filename.

To also include your own local `twitter.properties` file with your Twitter credentials,
use the target `privilegedDistZip` to make a special distribution in `PROJECT_ROOT/build/distributions` that starts with .


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
archive (i.e. in the `get-twitter-user` directory). Otherwise, if you've just built
the app from source, do this from within `PROJECT_ROOT/build/install/get-twitter-user`:
<pre>
Usage: bin/get-twitter-user[.bat] [options]
  Options:
     -c, --credentials
         Properties file with Twitter OAuth credential
         Default: ./twitter.properties
     -f, --include-favourite-media
         Include media from favourite tweets
         Default: false
     -i, --include-media
         Include media from tweets
         Default: false
     -o, --output
         Directory to which to write output
         Default: ./output
     -s, --screen-name
         Twitter screen name
     -u, --user-id
         Twitter user ID
     -debug
         Debug mode
         Default: false
</pre>

Run the app with your desired Twitter screen name, e.g. `weberdc`:
<pre>
prompt> bin/get-twitter-user --screen-name weberdc --include-media --include-favourite-media
</pre>

This will create a directory `output/weberdc` and download the following:

 + `@weberdc`'s profile to `output/weberdc/profile.json`
 + `@weberdc`'s tweets, one per file, to `output/weberdc/statuses/`
 + `@weberdc`'s favourited tweets, one per file, to `output/weberdc/favourites`
 + `@weberdc`'s images and other media mentioned, to `output/weberdc/media`

Attempts have been made to account for Twitter's rate limits, so at times the
app will pause, waiting until the rate limit has refreshed. It reports how long
it will wait when it does have to pause.
