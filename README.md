# servedoc.java

Simple, no-dependencies javadoc server for Java 19+.

## Installation

Paste and run the following on the terminal:

```
curl -s "https://raw.githubusercontent.com/andirady/servedoc/main/get.sh" | bash
```

## Usage

```
servedoc.java [-p port|--port port] groupId:artifactId:version
```

``servedoc.java`` will download the javadoc artifact from maven central repository and
place it in current user's local maven repository.
