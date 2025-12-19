package io.apidocx.handle.markdown;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import io.apidocx.config.ApidocxConfig;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 按 module 推断 Maven/Gradle 坐标与服务地址（无需依赖 Maven/Gradle IDEA 插件）。
 *
 * <p>优先级：
 * <ol>
 *     <li>用户在 .yapix 中配置的 llmMavenDependency/llmServiceEndpoint</li>
 *     <li>从当前 module 的 pom.xml / build.gradle(.kts) 推断</li>
 * </ol>
 */
public class LlmProjectInfoResolver {

    public static class Coordinates {
        public final String groupId;
        public final String artifactId;
        public final String version;
        public final String parentArtifactId;

        public Coordinates(String groupId, String artifactId, String version, String parentArtifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.parentArtifactId = parentArtifactId;
        }
    }

    private final Map<String, Optional<Coordinates>> coordsCache = new ConcurrentHashMap<>();

    public String resolveMavenDependencySnippet(ApidocxConfig config, Module module, VirtualFile sourceFile) {
        String configured = StringUtils.trimToNull(config.getLlmMavenDependency());
        if (configured != null) {
            return configured;
        }
        Coordinates coords = resolveCoordinates(module, sourceFile);
        if (coords == null || StringUtils.isAnyBlank(coords.groupId, coords.artifactId, coords.version)) {
            return null;
        }
        return "<dependency>\n"
                + "    <groupId>" + coords.groupId + "</groupId>\n"
                + "    <artifactId>" + coords.artifactId + "</artifactId>\n"
                + "    <version>" + coords.version + "</version>\n"
                + "</dependency>";
    }

    public String resolveServiceEndpoint(ApidocxConfig config, Module module, VirtualFile sourceFile) {
        String configured = StringUtils.trimToNull(config.getLlmServiceEndpoint());
        if (configured != null) {
            return configured;
        }
        Coordinates coords = resolveCoordinates(module, sourceFile);
        if (coords == null) {
            return null;
        }
        String artifact = StringUtils.defaultIfBlank(coords.parentArtifactId, coords.artifactId);
        if (StringUtils.isBlank(artifact)) {
            return null;
        }
        return "http://" + artifact;
    }

    public Coordinates resolveCoordinates(Module module, VirtualFile sourceFile) {
        if (module == null) {
            return null;
        }
        String cacheKey = module.getName() + ":" + (sourceFile != null ? sourceFile.getPath() : "");
        String key = cacheKey;
        Optional<Coordinates> cached = coordsCache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }

        Coordinates coords = null;
        try {
            // 优先从源文件所在目录向上查找
            File startDir = sourceFile != null ? new File(sourceFile.getParent().getPath()) : null;
            coords = resolveFromDirChain(startDir, module != null ? module.getName() : null, 6);

            if (coords == null) {
                File moduleRoot = findModuleRootDir(module);
                if (moduleRoot != null) {
                    coords = resolveFromPom(moduleRoot);
                    if (coords == null) {
                        coords = resolveFromGradle(moduleRoot, module.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        coordsCache.put(key, Optional.ofNullable(coords));
        return coords;
    }

    private Coordinates resolveFromDirChain(File startDir, String defaultArtifactId, int depth) {
        File cur = startDir;
        for (int i = 0; i < depth && cur != null; i++) {
            Coordinates coords = resolveFromPom(cur);
            if (coords == null) {
                coords = resolveFromGradle(cur, defaultArtifactId);
            }
            if (coords != null) {
                return coords;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    private File findModuleRootDir(Module module) {
        return Optional.ofNullable(ModuleRootManager.getInstance(module).getContentRoots())
                .filter(arr -> arr.length > 0)
                .map(arr -> {
                    // 优先选择包含构建文件的 content root
                    for (com.intellij.openapi.vfs.VirtualFile root : arr) {
                        if (root == null) {
                            continue;
                        }
                        File dir = new File(root.getPath());
                        if (new File(dir, "pom.xml").exists()
                                || new File(dir, "build.gradle").exists()
                                || new File(dir, "build.gradle.kts").exists()) {
                            return dir;
                        }
                    }
                    return new File(arr[0].getPath());
                })
                .orElse(null);
    }

    private Coordinates resolveFromPom(File moduleRoot) {
        File pom = new File(moduleRoot, "pom.xml");
        if (!pom.exists()) {
            // 向上找（多 module 场景：moduleRoot 可能是子目录）
            File cur = moduleRoot;
            for (int i = 0; i < 4 && cur != null; i++) {
                File tryPom = new File(cur, "pom.xml");
                if (tryPom.exists()) {
                    pom = tryPom;
                    break;
                }
                cur = cur.getParentFile();
            }
        }
        if (!pom.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(fis);
            Element project = doc.getDocumentElement();

            String artifactId = firstText(project, "artifactId");
            String groupId = firstText(project, "groupId");
            String version = firstText(project, "version");

            Element parent = firstElement(project, "parent");
            String parentGroupId = parent != null ? firstText(parent, "groupId") : null;
            String parentVersion = parent != null ? firstText(parent, "version") : null;
            String parentArtifactId = parent != null ? firstText(parent, "artifactId") : null;

            if (StringUtils.isBlank(groupId)) {
                groupId = parentGroupId;
            }
            if (StringUtils.isBlank(version)) {
                version = parentVersion;
            }

            // 简单解析 properties 占位符（常见 revision / project.version）
            Map<String, String> props = PomPropertyParser.parse(project);
            groupId = PomPropertyParser.resolve(groupId, props);
            artifactId = PomPropertyParser.resolve(artifactId, props);
            version = PomPropertyParser.resolve(version, props);
            parentArtifactId = PomPropertyParser.resolve(parentArtifactId, props);

            if (StringUtils.isAllBlank(groupId, artifactId, version)) {
                return null;
            }
            return new Coordinates(groupId, artifactId, version, parentArtifactId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Coordinates resolveFromGradle(File moduleRoot, String defaultArtifactId) {
        File gradle = new File(moduleRoot, "build.gradle");
        File gradleKts = new File(moduleRoot, "build.gradle.kts");
        File file = gradle.exists() ? gradle : (gradleKts.exists() ? gradleKts : null);
        if (file == null) {
            return null;
        }

        try {
            String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String group = firstRegex(text, "(?m)^\\s*group\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*$");
            if (group == null) {
                group = firstRegex(text, "(?m)^\\s*group\\s+['\\\"]([^'\\\"]+)['\\\"]\\s*$");
            }
            String version = firstRegex(text, "(?m)^\\s*version\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*$");

            String artifact = firstRegex(text, "(?m)^\\s*archivesBaseName\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*$");
            if (artifact == null) {
                artifact = firstRegex(text, "(?m)^\\s*archivesName\\.set\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)\\s*$");
            }
            if (artifact == null) {
                artifact = firstRegex(text, "(?m)^\\s*archivesName\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*$");
            }
            if (artifact == null) {
                artifact = StringUtils.defaultIfBlank(defaultArtifactId, null);
            }

            if (StringUtils.isAllBlank(group, artifact, version)) {
                return null;
            }
            return new Coordinates(group, artifact, version, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstRegex(String text, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static Element firstElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) {
                continue;
            }
            Element el = (Element) children.item(i);
            if (tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String firstText(Element parent, String tag) {
        Element el = firstElement(parent, tag);
        if (el == null) {
            return null;
        }
        String text = el.getTextContent();
        return text != null ? text.trim() : null;
    }

    private static class PomPropertyParser {
        private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

        static Map<String, String> parse(Element project) {
            Map<String, String> props = new ConcurrentHashMap<>();
            Element properties = firstElement(project, "properties");
            if (properties == null) {
                return props;
            }
            NodeList children = properties.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element)) {
                    continue;
                }
                Element el = (Element) children.item(i);
                String key = el.getTagName();
                String val = StringUtils.trimToNull(el.getTextContent());
                if (val != null) {
                    props.put(key, val);
                }
            }
            // 常见内置引用
            String projectVersion = firstText(project, "version");
            if (projectVersion != null) {
                props.putIfAbsent("project.version", projectVersion);
            }
            return props;
        }

        static String resolve(String raw, Map<String, String> props) {
            if (raw == null) {
                return null;
            }
            String s = raw.trim();
            for (int i = 0; i < 3; i++) {
                Matcher m = PLACEHOLDER.matcher(s);
                if (!m.find()) {
                    return s;
                }
                String key = m.group(1);
                String repl = props.get(key);
                if (repl == null) {
                    // 常见占位符
                    repl = props.get(key.toLowerCase(Locale.ROOT));
                }
                if (repl == null) {
                    return s;
                }
                s = s.replace("${" + key + "}", repl);
            }
            return s;
        }
    }
}
