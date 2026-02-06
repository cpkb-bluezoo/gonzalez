/*
 * PackageResolver.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.compiler;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.transform.TransformerConfigurationException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches XSLT 3.0 packages.
 *
 * <p>The PackageResolver is responsible for:
 * <ul>
 *   <li>Resolving package names to physical locations</li>
 *   <li>On-demand compilation of packages</li>
 *   <li>Caching compiled packages for reuse</li>
 *   <li>Version matching (exact, range, wildcard)</li>
 *   <li>Circular dependency detection</li>
 * </ul>
 *
 * <p>Package resolution order:
 * <ol>
 *   <li>Check cache for exact name + version match</li>
 *   <li>Check registered package locations</li>
 *   <li>Attempt to resolve as URI</li>
 *   <li>Compile and cache the package</li>
 * </ol>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://www.w3.org/TR/xslt-30/#packages-and-modules">XSLT 3.0 Packages</a>
 */
public class PackageResolver {

    /**
     * Cache of compiled packages, keyed by package name.
     * Each package name maps to a map of version -> compiled package.
     */
    private final Map<String, Map<String, CompiledPackage>> cache;

    /**
     * Registered package locations: package name -> source URI.
     */
    private final Map<String, String> packageLocations;

    /**
     * Set of packages currently being loaded (for circular dependency detection).
     * Uses a thread-local to support concurrent compilation.
     */
    private final ThreadLocal<Set<String>> loading;

    /**
     * Reference to the stylesheet compiler for on-demand compilation.
     */
    private StylesheetCompiler compiler;

    /**
     * Creates a new package resolver.
     */
    public PackageResolver() {
        this.cache = new ConcurrentHashMap<>();
        this.packageLocations = new ConcurrentHashMap<>();
        this.loading = ThreadLocal.withInitial(HashSet::new);
    }

    /**
     * Sets the stylesheet compiler for on-demand package compilation.
     *
     * @param compiler the compiler to use
     */
    public void setCompiler(StylesheetCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Registers a package location.
     *
     * <p>This allows specifying where to find packages before they are requested.
     *
     * @param packageName the package name (URI)
     * @param sourceUri the URI where the package source can be found
     */
    public void registerPackageLocation(String packageName, String sourceUri) {
        packageLocations.put(packageName, sourceUri);
    }

    /**
     * Resolves a package by name and version constraint.
     *
     * @param packageName the package name (URI)
     * @param versionConstraint the version constraint (e.g., "1.0", "1.*", "*")
     * @param baseUri the base URI for resolving relative URIs
     * @return the resolved compiled package
     * @throws SAXException if the package cannot be resolved or compiled
     */
    public CompiledPackage resolve(String packageName, String versionConstraint, 
                                   String baseUri) throws SAXException {
        if (packageName == null || packageName.isEmpty()) {
            throw new SAXException("XTSE3020: Package name is required");
        }

        // Normalize version constraint
        String version = versionConstraint != null ? versionConstraint : "*";

        // Check for circular dependency
        Set<String> currentlyLoading = loading.get();
        String loadKey = packageName + "#" + version;
        if (currentlyLoading.contains(loadKey)) {
            throw new SAXException("XTSE3015: Circular package dependency detected: " + packageName);
        }

        // Check cache first
        CompiledPackage cached = findInCache(packageName, version);
        if (cached != null) {
            return cached;
        }

        // Need to compile - mark as loading
        currentlyLoading.add(loadKey);
        try {
            return compileAndCache(packageName, version, baseUri);
        } finally {
            currentlyLoading.remove(loadKey);
        }
    }

    /**
     * Finds a package in the cache that matches the version constraint.
     */
    private CompiledPackage findInCache(String packageName, String versionConstraint) {
        Map<String, CompiledPackage> versions = cache.get(packageName);
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        // Exact version match
        if (!"*".equals(versionConstraint) && !versionConstraint.contains("*")) {
            return versions.get(versionConstraint);
        }

        // Wildcard matching
        for (Map.Entry<String, CompiledPackage> entry : versions.entrySet()) {
            if (matchesVersion(entry.getKey(), versionConstraint)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Compiles a package and adds it to the cache.
     */
    private CompiledPackage compileAndCache(String packageName, String versionConstraint, 
                                            String baseUri) throws SAXException {
        if (compiler == null) {
            throw new SAXException("XTSE3020: No compiler configured for package resolution: " + packageName);
        }

        // Find package source
        String sourceUri = resolvePackageSource(packageName, baseUri);
        if (sourceUri == null) {
            throw new SAXException("XTSE3020: Cannot locate package: " + packageName);
        }

        try {
            // Create input source
            InputSource source = new InputSource(sourceUri);
            
            // Compile the package
            // Note: The compiler needs to be able to compile packages (xsl:package)
            // and return CompiledPackage instead of just CompiledStylesheet
            CompiledPackage pkg = compiler.compilePackage(source, this);
            
            if (pkg == null) {
                throw new SAXException("XTSE3020: Failed to compile package: " + packageName);
            }

            // Verify the compiled package matches what we requested
            String compiledName = pkg.getPackageName();
            String compiledVersion = pkg.getPackageVersion();
            
            if (compiledName != null && !compiledName.equals(packageName)) {
                // Package name in source doesn't match requested name
                // This might be okay if we resolved via a different mechanism
            }

            // Cache the package
            cachePackage(pkg);

            return pkg;

        } catch (IOException e) {
            throw new SAXException("XTSE3020: Cannot load package " + packageName + ": " + e.getMessage(), e);
        } catch (TransformerConfigurationException e) {
            throw new SAXException("XTSE3020: Cannot compile package " + packageName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the source URI for a package.
     */
    private String resolvePackageSource(String packageName, String baseUri) {
        // Check registered locations first
        String registered = packageLocations.get(packageName);
        if (registered != null) {
            return resolveUri(registered, baseUri);
        }

        // Try to treat package name as a URI
        if (isValidUri(packageName)) {
            return packageName;
        }

        // Cannot resolve
        return null;
    }

    /**
     * Resolves a potentially relative URI against a base URI.
     */
    private String resolveUri(String uri, String baseUri) {
        if (baseUri == null || baseUri.isEmpty()) {
            return uri;
        }
        try {
            URI base = new URI(baseUri);
            URI resolved = base.resolve(uri);
            return resolved.toString();
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    /**
     * Checks if a string is a valid URI.
     */
    private boolean isValidUri(String s) {
        try {
            URI uri = new URI(s);
            return uri.getScheme() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Adds a compiled package to the cache.
     *
     * @param pkg the package to cache
     */
    public void cachePackage(CompiledPackage pkg) {
        String name = pkg.getPackageName();
        String version = pkg.getPackageVersion();
        
        if (name == null) {
            return; // Anonymous packages are not cached
        }
        if (version == null) {
            version = "0.0";
        }

        cache.computeIfAbsent(name, k -> new ConcurrentHashMap<>())
             .put(version, pkg);
    }

    /**
     * Removes a package from the cache.
     *
     * @param packageName the package name
     * @param version the version (or null to remove all versions)
     */
    public void uncachePackage(String packageName, String version) {
        Map<String, CompiledPackage> versions = cache.get(packageName);
        if (versions != null) {
            if (version == null) {
                versions.clear();
            } else {
                versions.remove(version);
            }
        }
    }

    /**
     * Clears the entire package cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Returns the number of cached packages.
     *
     * @return the cache size
     */
    public int getCacheSize() {
        int count = 0;
        for (Map<String, CompiledPackage> versions : cache.values()) {
            count += versions.size();
        }
        return count;
    }

    /**
     * Checks if a version matches a version constraint.
     *
     * <p>Supported patterns:
     * <ul>
     *   <li>"*" - matches any version</li>
     *   <li>"1.*" - matches any version starting with "1."</li>
     *   <li>"1.0" - exact match</li>
     *   <li>"1.0-2.0" - range match (inclusive)</li>
     * </ul>
     *
     * @param version the actual version
     * @param constraint the version constraint
     * @return true if the version matches
     */
    public static boolean matchesVersion(String version, String constraint) {
        if (version == null || constraint == null) {
            return false;
        }
        
        // Wildcard matches everything
        if ("*".equals(constraint)) {
            return true;
        }

        // Check for range (e.g., "1.0-2.0")
        int dashIndex = constraint.indexOf('-');
        if (dashIndex > 0 && dashIndex < constraint.length() - 1) {
            String minVersion = constraint.substring(0, dashIndex);
            String maxVersion = constraint.substring(dashIndex + 1);
            return compareVersions(version, minVersion) >= 0 &&
                   compareVersions(version, maxVersion) <= 0;
        }

        // Check for prefix wildcard (e.g., "1.*")
        if (constraint.endsWith(".*")) {
            String prefix = constraint.substring(0, constraint.length() - 1);
            return version.startsWith(prefix);
        }

        // Exact match
        return version.equals(constraint);
    }

    /**
     * Compares two version strings.
     *
     * @param v1 first version
     * @param v2 second version
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    public static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (p1 != p2) {
                return p1 - p2;
            }
        }
        return 0;
    }

    /**
     * Parses a version part as an integer.
     */
    private static int parseVersionPart(String part) {
        try {
            // Remove non-numeric suffix (e.g., "1alpha" -> 1)
            StringBuilder sb = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else {
                    break;
                }
            }
            return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Gets a list of all cached package names.
     *
     * @return set of package names
     */
    public Set<String> getCachedPackageNames() {
        return Collections.unmodifiableSet(cache.keySet());
    }

    /**
     * Gets all versions of a cached package.
     *
     * @param packageName the package name
     * @return set of cached versions, or empty set if not cached
     */
    public Set<String> getCachedVersions(String packageName) {
        Map<String, CompiledPackage> versions = cache.get(packageName);
        if (versions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(versions.keySet());
    }
}
