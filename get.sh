#!/bin/bash

installDir=$HOME/.local/bin
mkdir -p $installDir
curl -o $installDir/servedoc.java https://raw.githubusercontent.com/andirady/servedoc/main/servedoc.java
chmod +x $installDir/servedoc.java
