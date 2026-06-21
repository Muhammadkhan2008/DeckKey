#!/bin/bash

echo "=========================================="
echo "DeckKey APK Build Script"
echo "=========================================="

# Check Java
echo "✓ Java Version:"
java -version

# Create gradle wrapper JAR if missing
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo ""
    echo "⚠️  Gradle wrapper JAR missing. Downloading..."
    mkdir -p gradle/wrapper
    cd gradle/wrapper
    
    # Download gradle wrapper JAR
    curl -L -o gradle-wrapper.jar https://github.com/gradle/gradle/releases/download/v8.7/gradle-8.7-bin.zip 2>/dev/null || \
    wget -O gradle-8.7-bin.zip https://github.com/gradle/gradle/releases/download/v8.7/gradle-8.7-bin.zip 2>/dev/null || \
    echo "Download failed - checking alternative method"
    
    cd ../..
fi

echo ""
echo "=========================================="
echo "Building DeckKey APK..."
echo "=========================================="

# Try to build
if command -v ./gradlew &> /dev/null; then
    echo "Using gradlew..."
    ./gradlew assembleDebug
elif command -v gradlew.bat &> /dev/null; then
    echo "Using gradlew.bat..."
    ./gradlew.bat assembleDebug
else
    echo "Gradlew not found. Creating manual build configuration..."
fi

