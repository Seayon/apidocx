package io.apidocx.handle.markdown;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.util.PsiTypesUtil;
import io.apidocx.config.ApidocxConfig;
import io.apidocx.model.Api;
import io.apidocx.parse.constant.DocumentTags;
import io.apidocx.parse.constant.JavaConstants;
import io.apidocx.parse.constant.JsonRpcConstants;
import io.apidocx.parse.model.MethodApiData;
import io.apidocx.parse.parser.ParseHelper;
import io.apidocx.parse.util.PsiAnnotationUtils;
import io.apidocx.parse.util.PsiDocCommentUtils;
import io.apidocx.parse.util.PsiUtils;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Copy Api as Markdown for LLMs:
 * <ul>
 *     <li>按 JSON-RPC 文档风格输出（尽量贴近用户给的参考格式）</li>
 *     <li>入参/出参为类时，递归展开类/枚举/嵌套类型</li>
 *     <li>尽量把源码（PsiClass.getText）带出来，便于 LLM 看到注解（NotEmpty/NotBlank 等）</li>
 * </ul>
 */
public class LlmMarkdownGenerator {

    private final Project project;
    private final Module module;
    private final ApidocxConfig config;
    private final ParseHelper parseHelper;
    private final LlmProjectInfoResolver projectInfoResolver = new LlmProjectInfoResolver();

    public LlmMarkdownGenerator(Project project, Module module, ApidocxConfig config) {
        this.project = project;
        this.module = module;
        this.config = config;
        this.parseHelper = new ParseHelper(project, module);
    }

    public String generate(List<MethodApiData> methodDataList) {
        if (methodDataList == null || methodDataList.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (MethodApiData methodData : methodDataList) {
            if (methodData == null || methodData.getMethod() == null) {
                continue;
            }
            appendDocForMethod(sb, methodData);
            sb.append("\n---\n\n");
        }
        return trimTrailingDivider(sb.toString());
    }

    private void appendDocForMethod(StringBuilder sb, MethodApiData methodData) {
        PsiMethod method = methodData.getMethod();
        PsiClass serviceClass = method.getContainingClass();
        if (serviceClass == null) {
            return;
        }
        Module resolvedModule = Optional.ofNullable(ModuleUtilCore.findModuleForPsiElement(method)).orElse(module);
        VirtualFile sourceFile = Optional.ofNullable(method.getContainingFile())
                .map(PsiFile::getVirtualFile)
                .orElse(null);

        boolean isJsonRpc = serviceClass.getAnnotation(JsonRpcConstants.JsonRpcService) != null;
        sb.append("## ").append(isJsonRpc ? "JSON-RPC 接口文档" : "接口文档").append("\n\n");

        String mavenDependency = resolvedModule != null
                ? projectInfoResolver.resolveMavenDependencySnippet(config, resolvedModule, sourceFile)
                : StringUtils.trimToNull(config.getLlmMavenDependency());
        if (StringUtils.isNotBlank(mavenDependency)) {
            sb.append("**Maven 依赖**").append("\n");
            sb.append("```xml").append("\n");
            sb.append(mavenDependency.trim()).append("\n");
            sb.append("```").append("\n\n");
        }

        Api api = Optional.ofNullable(methodData.getApis()).orElseGet(Collections::emptyList).stream().findFirst().orElse(null);
        String serviceEndpoint = resolvedModule != null
                ? projectInfoResolver.resolveServiceEndpoint(config, resolvedModule, sourceFile)
                : StringUtils.trimToNull(config.getLlmServiceEndpoint());
        String interfaceFqn = StringUtils.defaultIfBlank(serviceClass.getQualifiedName(), serviceClass.getName());
        String description = resolveMethodDescription(api, method);

        if (isJsonRpc) {
            String interfaceId = resolveJsonRpcInterfaceId(api, serviceClass, method);
            String rpcPath = extractJsonRpcPath(interfaceId);
            sb.append("- 接口 ID: ").append(code(interfaceId)).append("\n");
            if (serviceEndpoint != null) {
                sb.append("- 服务地址: ").append(serviceEndpoint).append("\n");
            }
            sb.append("- 接口类: ").append(code(interfaceFqn)).append("\n");
            if (StringUtils.isNotBlank(rpcPath)) {
                sb.append("- RPC 路径: ").append(code(rpcPath)).append("\n");
            }
            sb.append("- 方法: ").append(code(method.getName())).append("\n");
            if (StringUtils.isNotBlank(description)) {
                sb.append("- 描述: ").append(escapeInline(description)).append("\n");
            }
            sb.append("\n");
        } else if (api != null) {
            sb.append("- 请求路径: ").append(code(api.getMethod().name() + " " + api.getPath())).append("\n");
            if (serviceEndpoint != null) {
                sb.append("- 服务地址: ").append(serviceEndpoint).append("\n");
            }
            sb.append("- 接口类: ").append(code(interfaceFqn)).append("\n");
            sb.append("- 方法: ").append(code(method.getName())).append("\n");
            if (StringUtils.isNotBlank(description)) {
                sb.append("- 描述: ").append(escapeInline(description)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("### Java 方法").append("\n\n");
        sb.append("```java").append("\n");
        sb.append(buildMethodSignature(method)).append("\n");
        sb.append("```").append("\n\n");

        // 递归类型定义（类 / 枚举 / 嵌套类）
        Set<PsiClass> types = new TypeCollector().collect(method, resolveBusinessReturnType(method));
        if (!types.isEmpty()) {
            sb.append("### 源码").append("\n\n");
            for (PsiClass type : types) {
                appendTypeDefinition(sb, type);
            }
        }
    }

    private void appendTypeDefinition(StringBuilder sb, PsiClass type) {
        String name = StringUtils.defaultIfBlank(type.getName(), type.getQualifiedName());
        sb.append("#### ").append(code(name)).append("\n\n");

        sb.append("```java").append("\n");
        sb.append(type.getText()).append("\n");
        sb.append("```").append("\n\n");
    }

    private boolean isRequiredParameter(PsiParameter p) {
        if (p == null) {
            return false;
        }
        if (p.getType() instanceof PsiPrimitiveType) {
            return true;
        }
        String[] requiredAnnotations = {
                JavaConstants.NotNull, JavaConstants.NotNull2,
                JavaConstants.NotBlank, JavaConstants.NotBlank2, JavaConstants.NotBlank3,
                JavaConstants.NotEmpty, JavaConstants.NotEmpty2, JavaConstants.NotEmpty3
        };
        return Arrays.stream(requiredAnnotations).anyMatch(a -> p.getAnnotation(a) != null);
    }

    private String buildMethodSignature(PsiMethod method) {
        // 用户希望“原样复制方法签名”，这里优先直接取源码文本；若有方法体则截掉 body，保留签名部分。
        String text = method.getText();
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return text.trim();
        }
        int methodStart = method.getTextRange().getStartOffset();
        int bodyStart = body.getTextRange().getStartOffset();
        int index = bodyStart - methodStart;
        if (index <= 0 || index > text.length()) {
            return text.trim();
        }
        return text.substring(0, index).trim() + ";";
    }

    private PsiType resolveBusinessReturnType(PsiMethod method) {
        if (method == null) {
            return null;
        }
        PsiType returnType = method.getReturnType();
        if (returnType == null) {
            return null;
        }
        String[] parts = io.apidocx.parse.util.PsiGenericUtils.splitTypeAndGenericPair(returnType.getCanonicalText());
        String raw = parts[0];
        if (StringUtils.isBlank(raw)) {
            return returnType;
        }
        List<String> unwrapTypes = Optional.ofNullable(config.getReturnUnwrapTypes()).orElseGet(Collections::emptyList);
        boolean needUnwrap = unwrapTypes.stream().anyMatch(t -> Objects.equals(t, raw));
        if (!needUnwrap) {
            return returnType;
        }
        if (returnType instanceof PsiClassType) {
            PsiType[] params = ((PsiClassType) returnType).getParameters();
            if (params.length >= 1) {
                return params[0];
            }
        }
        return returnType;
    }

    private PsiClass resolveMainPsiClass(PsiType type) {
        if (type == null) {
            return null;
        }

        PsiType cursor = type;
        // array
        if (cursor instanceof PsiArrayType) {
            cursor = ((PsiArrayType) cursor).getComponentType();
        }
        // wildcard
        if (cursor instanceof PsiWildcardType) {
            cursor = ((PsiWildcardType) cursor).getBound();
        }
        // class type + 泛型：优先取单个泛型参数
        if (cursor instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) cursor;
            PsiType[] parameters = classType.getParameters();
            if (parameters.length == 1) {
                PsiClass paramClass = PsiTypesUtil.getPsiClass(parameters[0]);
                if (paramClass != null) {
                    return paramClass;
                }
            }
            return classType.resolve();
        }
        return PsiTypesUtil.getPsiClass(cursor);
    }

    private boolean isDocumentableUserType(PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }
        String qn = psiClass.getQualifiedName();
        if (StringUtils.isBlank(qn)) {
            return false;
        }
        if (qn.startsWith("java.") || qn.startsWith("javax.") || qn.startsWith("jakarta.")) {
            return false;
        }
        if (qn.startsWith("kotlin.") || qn.startsWith("scala.")) {
            return false;
        }
        if (qn.startsWith("org.springframework.") || qn.startsWith("org.jetbrains.") || qn.startsWith("com.intellij.")) {
            return false;
        }
        return true;
    }

    private String resolveJsonRpcInterfaceId(Api api, PsiClass serviceClass, PsiMethod method) {
        if (api != null && StringUtils.isNotBlank(api.getPath())) {
            return api.getPath();
        }
        PsiAnnotation annotation = serviceClass.getAnnotation(JsonRpcConstants.JsonRpcService);
        if (annotation == null) {
            return method.getName();
        }
        List<String> paths = PsiAnnotationUtils.getStringArrayAttribute(annotation, "path");
        if (paths == null || paths.isEmpty()) {
            paths = PsiAnnotationUtils.getStringArrayAttribute(annotation, "value");
        }
        String basePath = (paths != null && !paths.isEmpty()) ? paths.get(0) : "";
        if (StringUtils.isBlank(basePath)) {
            return "#" + method.getName();
        }
        return basePath + "#" + method.getName();
    }

    private String extractJsonRpcPath(String interfaceId) {
        if (StringUtils.isBlank(interfaceId)) {
            return null;
        }
        int idx = interfaceId.indexOf('#');
        return idx >= 0 ? interfaceId.substring(0, idx) : interfaceId;
    }

    private String resolveMethodDescription(Api api, PsiMethod method) {
        if (api != null && StringUtils.isNotBlank(api.getDescription())) {
            return api.getDescription();
        }
        String title = PsiDocCommentUtils.getDocCommentTitle(method);
        String desc = PsiDocCommentUtils.getDocCommentDescription(method);
        if (StringUtils.isNotBlank(desc)) {
            return title != null ? title + " " + desc : desc;
        }
        return title;
    }

    private String requiredMarker(boolean required) {
        return required ? " *" : "";
    }

    private String code(String v) {
        return "`" + StringUtils.defaultString(v).replace("`", "\\`") + "`";
    }

    private String escapeInline(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", "").replace("\n", " ").trim();
    }

    private String trimTrailingDivider(String s) {
        String trimmed = s;
        while (trimmed.endsWith("\n")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("---")) {
            int idx = trimmed.lastIndexOf("---");
            trimmed = trimmed.substring(0, idx);
        }
        return trimmed.trim() + "\n";
    }

    private class TypeCollector {

        private final LinkedHashMap<String, PsiClass> visited = new LinkedHashMap<>();
        private final Set<String> excluded = new LinkedHashSet<>(Arrays.asList(
                "java.", "javax.", "jakarta.", "kotlin.", "scala.",
                "org.springframework.", "org.jetbrains.", "com.intellij."
        ));

        Set<PsiClass> collect(PsiMethod method, PsiType returnType) {
            Deque<PsiType> queue = new ArrayDeque<>();
            for (PsiParameter p : method.getParameterList().getParameters()) {
                queue.add(p.getType());
            }
            if (returnType != null) {
                queue.add(returnType);
            }
            while (!queue.isEmpty()) {
                PsiType type = queue.poll();
                collectType(queue, type);
            }
            return new LinkedHashSet<>(visited.values());
        }

        private void collectType(Deque<PsiType> queue, PsiType type) {
            if (type == null) {
                return;
            }
            if (type instanceof PsiPrimitiveType) {
                return;
            }
            if (type instanceof PsiArrayType) {
                queue.add(((PsiArrayType) type).getComponentType());
                return;
            }
            if (type instanceof PsiWildcardType) {
                queue.add(((PsiWildcardType) type).getBound());
                return;
            }
            if (type instanceof PsiClassType) {
                PsiClassType ct = (PsiClassType) type;
                queue.addAll(Arrays.asList(ct.getParameters()));
                PsiClass cls = ct.resolve();
                collectClass(queue, cls);
                return;
            }
            PsiClass cls = PsiTypesUtil.getPsiClass(type);
            collectClass(queue, cls);
        }

        private void collectClass(Deque<PsiType> queue, PsiClass psiClass) {
            if (psiClass == null) {
                return;
            }
            String qn = psiClass.getQualifiedName();
            if (StringUtils.isBlank(qn)) {
                return;
            }
            if (excluded.stream().anyMatch(qn::startsWith)) {
                return;
            }
            if (visited.containsKey(qn)) {
                return;
            }
            visited.put(qn, psiClass);

            if (psiClass.isEnum()) {
                return;
            }

            // 字段类型递归
            parseHelper.getFields(psiClass).stream()
                    .filter(f -> !f.hasModifierProperty(PsiModifier.STATIC))
                    .forEach(f -> queue.add(f.getType()));

            // @see 引用类递归（接口/抽象类常用）
            Set<String> seeTypes = PsiDocCommentUtils.getTagTextSet(psiClass, DocumentTags.See);
            for (String t : seeTypes) {
                PsiClass ref = Optional.ofNullable(PsiUtils.findPsiClass(project, module, t))
                        .orElse(PsiUtils.findPsiClassByShortName(project, module, t));
                if (ref != null) {
                    collectClass(queue, ref);
                }
            }
        }
    }
}
