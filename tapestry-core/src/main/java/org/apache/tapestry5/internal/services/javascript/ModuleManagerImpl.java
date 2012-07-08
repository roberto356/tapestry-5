// Copyright 2012 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.services.javascript;

import org.apache.tapestry5.Asset;
import org.apache.tapestry5.SymbolConstants;
import org.apache.tapestry5.annotations.Path;
import org.apache.tapestry5.dom.Element;
import org.apache.tapestry5.func.F;
import org.apache.tapestry5.func.Worker;
import org.apache.tapestry5.internal.services.assets.ResourceChangeTracker;
import org.apache.tapestry5.ioc.Messages;
import org.apache.tapestry5.ioc.Resource;
import org.apache.tapestry5.ioc.annotations.PostInjection;
import org.apache.tapestry5.ioc.annotations.Symbol;
import org.apache.tapestry5.ioc.internal.util.CollectionFactory;
import org.apache.tapestry5.ioc.internal.util.InternalUtils;
import org.apache.tapestry5.json.JSONArray;
import org.apache.tapestry5.json.JSONObject;
import org.apache.tapestry5.services.AssetSource;
import org.apache.tapestry5.services.ComponentClassResolver;
import org.apache.tapestry5.services.assets.AssetPathConstructor;
import org.apache.tapestry5.services.javascript.ModuleManager;
import org.apache.tapestry5.services.javascript.ShimModule;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ModuleManagerImpl implements ModuleManager
{
    private final String requireConfig;

    private final Asset requireJS;
    private final Messages globalMessages;

    private final boolean compactJSON;

    // Library names, sorted by order of descending length.
    private final List<String> libraryNames;

    private final Map<String, List<String>> libraryNameToPackageNames = CollectionFactory.newMap();

    private final Map<String, Resource> shimModuleNameToResource = CollectionFactory.newMap();

    private final Resource classpathRoot;

    // Note: ConcurrentHashMap does not support null as a value, alas. We use classpathRoot as a null.
    private final Map<String, Resource> cache = CollectionFactory.newConcurrentMap();

    public ModuleManagerImpl(AssetPathConstructor constructor, final ComponentClassResolver resolver, AssetSource assetSource,
                             @Path("${" + SymbolConstants.REQUIRE_JS + "}")
                             Asset requireJS,
                             Map<String, ShimModule> configuration,
                             Messages globalMessages, @Symbol(SymbolConstants.COMPACT_JSON)
    boolean compactJSON)
    {
        this.requireJS = requireJS;
        this.globalMessages = globalMessages;
        this.compactJSON = compactJSON;

        this.requireConfig = buildRequireJSConfig(constructor.constructAssetPath("module-root", ""), configuration);

        classpathRoot = assetSource.resourceForPath("");

        libraryNames = F.flow(resolver.getLibraryNames())
                .each(new Worker<String>()
                {
                    @Override
                    public void work(String element)
                    {
                        libraryNameToPackageNames.put(element, resolver.getPackagesForLibrary(element));
                    }
                })
                .append("app")
                .sort(new Comparator<String>()
                {
                    @Override
                    public int compare(String o1, String o2)
                    {
                        return o2.length() - o1.length();
                    }
                }).toList();

        libraryNameToPackageNames.put("app", resolver.getPackagesForLibrary(""));
    }

    private String buildRequireJSConfig(String baseURL, Map<String, ShimModule> configuration)
    {
        JSONObject shims = new JSONObject();
        JSONObject config = new JSONObject().put("baseUrl", baseURL).put("shim", shims);

        for (String name : configuration.keySet())
        {
            ShimModule module = configuration.get(name);

            shimModuleNameToResource.put(name, module.resource);

            JSONObject shim = new JSONObject();

            if (module.dependencies != null && !module.dependencies.isEmpty())
            {
                for (String dep : module.dependencies)
                {
                    shim.accumulate("deps", dep);
                }
            }

            if (InternalUtils.isNonBlank(module.exports))
            {
                shim.put("exports", module.exports);
            }

            shims.put(name, shim);
        }

        return String.format("require.config(%s);\n",
                config.toString(compactJSON));
    }

    @PostInjection
    public void setupInvalidation(ResourceChangeTracker tracker)
    {
        tracker.clearOnInvalidation(cache);
    }

    public void writeInitialization(Element body, List<String> scriptURLs, List<JSONArray> immediateInits, List<JSONArray> deferredInits)
    {
        body.element("script", "src", requireJS.toClientURL());

        Element element = body.element("script", "type", "text/javascript");

        element.raw(requireConfig);

        StringBuilder content = new StringBuilder(1000);

        content.append(globalMessages.format("core-page-initialization-template",
                convert(scriptURLs),
                convert(immediateInits),
                convert(deferredInits)));

        element.raw(content.toString());
    }

    private String convert(List<?> input)
    {
        JSONArray array = new JSONArray();

        for (Object o : input)
        {
            array.put(o);
        }

        return array.toString(compactJSON);
    }

    @Override
    public Resource findResourceForModule(String moduleName)
    {
        Resource resource = cache.get(moduleName);

        if (resource == null)
        {
            resource = resolveModuleNameToResource(moduleName);
            cache.put(moduleName, resource);
        }

        // We're treating classpathRoot as a placeholder for null.

        return resource == classpathRoot ? null : resource;
    }

    private Resource resolveModuleNameToResource(String moduleName)
    {
        Resource resource = shimModuleNameToResource.get(moduleName);

        if (resource != null)
        {
            return resource;
        }

        // Look for the longest match.

        for (String library : libraryNames)
        {
            int len = library.length();

            if (moduleName.length() <= len)
            {
                continue;
            }

            if (moduleName.startsWith(library) && moduleName.charAt(len) == '/')
            {
                return findResourceInsideLibrary(library, moduleName);
            }
        }

        return classpathRoot;
    }

    private Resource findResourceInsideLibrary(String library, String moduleName)
    {
        String extra = moduleName.substring(library.length() + 1);

        List<String> packages = libraryNameToPackageNames.get(library);

        for (String p : packages)
        {
            String baseName = p.replace('.', '/') + "/modulejs/" + extra;

            Resource baseResource = classpathRoot.forFile(baseName);

            // TODO: Figure out which suffixes to try for. More configuration, somewhere, to indicate
            // that .coffee will be converted to .js. Maybe add a method to ResourceTransformer; the MIME type
            // produced.

            Resource resource = baseResource.withExtension("js");

            if (resource.exists())
            {
                return resource;
            }
        }

        // Not found in any package

        return classpathRoot;
    }
}