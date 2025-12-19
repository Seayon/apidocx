package io.apidocx.handle.markdown;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import io.apidocx.action.AbstractAction;
import io.apidocx.base.util.ClipboardUtils;
import io.apidocx.base.util.NotificationUtils;
import io.apidocx.base.util.PsiFileUtils;
import io.apidocx.base.util.PsiModuleUtils;
import io.apidocx.config.ApidocxConfig;
import io.apidocx.model.Api;
import io.apidocx.parse.constant.JsonRpcConstants;
import io.apidocx.parse.constant.SpringConstants;
import io.apidocx.parse.ApiParser;
import io.apidocx.parse.model.ClassApiData;
import io.apidocx.parse.model.MethodApiData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Copy Api as Markdown for LLMs (包含源码)
 */
public class CopyApiAsMarkdownForLlmsAction extends AbstractAction {

    public static final String ACTION_TEXT = "Copy Api as Markdown for LLMs";

    public CopyApiAsMarkdownForLlmsAction() {
        super(false);
    }

    @Override
    public boolean before(AnActionEvent event, ApidocxConfig config) {
        // LLM 场景更偏向“尽可能多的上下文”，不应被 strict 模式拦截
        config.setStrict(false);
        return true;
    }

    @Override
    public void handle(AnActionEvent event, ApidocxConfig config, List<Api> apis) {
        Project project = event.getData(CommonDataKeys.PROJECT);
        Module module = Optional.ofNullable(event.getData(LangDataKeys.MODULE))
                .orElseGet(() -> PsiModuleUtils.findModuleByEvent(event));
        if (project == null || module == null) {
            NotificationUtils.notifyWarning(ACTION_TEXT, "project/module not found");
            return;
        }

        Selection selection = Selection.resolve(event, project);
        ApiParser apiParser = new ApiParser(project, module, config);

        List<MethodApiData> methodDataList = new ArrayList<>();
        if (selection.selectedMethod != null) {
            MethodApiData methodData = apiParser.parse(selection.selectedMethod);
            if (methodData.isValid()) {
                methodDataList.add(methodData);
            }
        } else if (selection.selectedClass != null) {
            ClassApiData classData = apiParser.parse(selection.selectedClass);
            methodDataList.addAll(Optional.ofNullable(classData.getMethodDataList()).orElseGet(Collections::emptyList)
                    .stream()
                    .filter(MethodApiData::isValid)
                    .collect(Collectors.toList()));
        } else if (selection.selectedJavaFiles != null && !selection.selectedJavaFiles.isEmpty()) {
            List<PsiClass> classes = PsiFileUtils.getPsiClassByFile(selection.selectedJavaFiles);
            for (PsiClass psiClass : classes) {
                ClassApiData classData = apiParser.parse(psiClass);
                Optional.ofNullable(classData.getMethodDataList()).orElseGet(Collections::emptyList)
                        .stream()
                        .filter(MethodApiData::isValid)
                        .forEach(methodDataList::add);
            }
        }

        if (methodDataList.isEmpty()) {
            NotificationUtils.notifyWarning(ACTION_TEXT, "not found valid api");
            return;
        }

        String markdown = new LlmMarkdownGenerator(project, module, config).generate(methodDataList);
        ClipboardUtils.setClipboard(markdown);
        NotificationUtils.notifyInfo(ACTION_TEXT, "copied to clipboard");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setText(ACTION_TEXT);
        Project project = e.getProject();
        boolean available = project != null
                && ReadAction.compute(() -> Selection.resolve(e, project).isSupportedContext());
        e.getPresentation().setEnabledAndVisible(available);
    }

    private static class Selection {
        PsiMethod selectedMethod;
        PsiClass selectedClass;
        List<PsiJavaFile> selectedJavaFiles;

        static Selection resolve(AnActionEvent event, Project project) {
            Selection selection = new Selection();

            VirtualFile[] selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (selectedFiles != null && selectedFiles.length > 0) {
                selection.selectedJavaFiles = PsiFileUtils.getPsiJavaFiles(project, selectedFiles);
            }

            Editor editor = event.getDataContext().getData(CommonDataKeys.EDITOR);
            PsiFile editorFile = event.getDataContext().getData(CommonDataKeys.PSI_FILE);
            if (editor != null && editorFile != null) {
                PsiElement referenceAt = editorFile.findElementAt(editor.getCaretModel().getOffset());
                selection.selectedMethod = PsiTreeUtil.getContextOfType(referenceAt, PsiMethod.class);
                selection.selectedClass = PsiTreeUtil.getContextOfType(referenceAt, PsiClass.class);
            }

            // 兼容：如果光标落在方法上，class 也会同时存在，这里保留 method 优先
            if (selection.selectedMethod != null) {
                selection.selectedClass = null;
            }

            if (selection.selectedJavaFiles != null) {
                selection.selectedJavaFiles = selection.selectedJavaFiles.stream().filter(Objects::nonNull).collect(Collectors.toList());
            }
            return selection;
        }

        boolean isSupportedContext() {
            if (selectedMethod != null) {
                PsiClass cls = selectedMethod.getContainingClass();
                if (cls == null) {
                    return false;
                }
                if (cls.getAnnotation(JsonRpcConstants.JsonRpcService) != null) {
                    return true;
                }
                return selectedMethod.getAnnotation(SpringConstants.RequestMapping) != null
                        || selectedMethod.getAnnotation(SpringConstants.GetMapping) != null
                        || selectedMethod.getAnnotation(SpringConstants.PostMapping) != null
                        || selectedMethod.getAnnotation(SpringConstants.PutMapping) != null
                        || selectedMethod.getAnnotation(SpringConstants.DeleteMapping) != null
                        || selectedMethod.getAnnotation(SpringConstants.PatchMapping) != null;
            }
            if (selectedClass != null) {
                return selectedClass.getAnnotation(JsonRpcConstants.JsonRpcService) != null
                        || selectedClass.getAnnotation(SpringConstants.RestController) != null
                        || selectedClass.getAnnotation(SpringConstants.Controller) != null;
            }
            return selectedJavaFiles != null && !selectedJavaFiles.isEmpty();
        }
    }
}
