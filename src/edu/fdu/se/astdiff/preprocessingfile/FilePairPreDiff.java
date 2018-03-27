package edu.fdu.se.astdiff.preprocessingfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;


import edu.fdu.se.config.ProjectProperties;
import edu.fdu.se.config.PropertyKeys;
import edu.fdu.se.javaparser.JDTParserFactory;
import org.eclipse.jdt.core.dom.*;

/**
 * 两个文件 预处理
 * 删除一摸一样的方法
 * 删除一摸一样的field
 * 删除一摸一样的内部类
 * 删除add method
 * 删除remove method
 * 删除内部类中的add / remove method
 * 保留 remove field 和add field 因为需要识别是否是refactor
 */
public class FilePairPreDiff {

    public static void main(String args[]) {
        new FilePairPreDiff().compareTwoFile("D:/Workspace/Android_Diff/SDK_Files_15-26/android-25/android/accessibilityservice/AccessibilityService.java",
                "D:/Workspace/Android_Diff/SDK_Files_15-26/android-26/android/accessibilityservice/AccessibilityService.java", "test_file");

    }

    public FilePairPreDiff() {
        preprocessedData = new PreprocessedData();
        preprocessedTempData = new PreprocessedTempData();
    }

    private PreprocessedData preprocessedData;
    private PreprocessedTempData preprocessedTempData;



    public void compareTwoFile(String src, String dst, String outputDirName) {
        ASTTraversal astTraversal = new ASTTraversal();
        CompilationUnit cuSrc = JDTParserFactory.getCompilationUnit(src);
        CompilationUnit cuDst = JDTParserFactory.getCompilationUnit(dst);
        preprocessedData.loadTwoCompilationUnits(cuSrc, cuDst, src, dst);
        FilePreprocessLog filePreprocessLog = null;
        if ("true".equals(ProjectProperties.getInstance().getValue(PropertyKeys.DEBUG_PREPROCESSING))) {
            filePreprocessLog = new FilePreprocessLog(outputDirName);
            filePreprocessLog.writeFileBeforeProcess(preprocessedData);
        }
        preprocessedTempData.removeAllSrcComments(cuSrc, preprocessedData.srcLines);
        preprocessedTempData.removeAllDstComments(cuDst, preprocessedData.dstLines);
        BodyDeclaration bodyDeclarationSrc = (BodyDeclaration) cuSrc.types().get(0);
        BodyDeclaration bodyDeclarationDst = (BodyDeclaration) cuDst.types().get(0);
        if (!(bodyDeclarationSrc instanceof TypeDeclaration) || !(bodyDeclarationDst instanceof TypeDeclaration)) {
            return;
        }
        TypeDeclaration mTypeSrc = (TypeDeclaration) bodyDeclarationSrc;
        TypeDeclaration mTypeDst = (TypeDeclaration) bodyDeclarationDst;
        astTraversal.traverseSrcTypeDeclarationInit(preprocessedData, preprocessedTempData, mTypeSrc, mTypeSrc.getName().toString() + ".");
        astTraversal.traverseDstTypeDeclarationCompareSrc(preprocessedData, preprocessedTempData, mTypeDst, mTypeDst.getName().toString() + ".");
        // 考虑后面的识别method name变化，这里把remove的注释掉
        iterateVisitingMap(astTraversal);
        undeleteSignatureChange();
        preprocessedTempData.removeSrcRemovalList(cuSrc, preprocessedData.srcLines);
        preprocessedTempData.removeDstRemovalList(cuDst,preprocessedData.dstLines);
        if (filePreprocessLog != null) {
            filePreprocessLog.writeFileAfterProcess(preprocessedData);
        }
    }



    private void iterateVisitingMap(ASTTraversal astTraversal) {
        for (Entry<BodyDeclarationPair, Integer> item : preprocessedTempData.srcNodeVisitingMap.entrySet()) {
            BodyDeclarationPair bdp = item.getKey();
            int value = item.getValue();
            BodyDeclaration bd = bdp.getBodyDeclaration();
            if (bd instanceof TypeDeclaration) {
                switch (value) {
//                    case PreprocessedTempData.BODY_DIFFERENT_RETAIN:
//                    case PreprocessedTempData.BODY_FATHERNODE_REMOVE:
//                        break;
                    case PreprocessedTempData.BODY_INITIALIZED_VALUE:
                        this.preprocessedData.addBodiesDeleted(bdp);
                        this.preprocessedTempData.addToSrcRemoveList(bd);
                        astTraversal.traverseTypeDeclarationSetVisited(preprocessedTempData, (TypeDeclaration) bd, bdp.getLocationClassString());
                        break;
                    case PreprocessedTempData.BODY_SAME_REMOVE:
                        this.preprocessedTempData.addToSrcRemoveList(bd);
                        break;
                }
            }
        }
        for (Entry<BodyDeclarationPair, Integer> item : preprocessedTempData.srcNodeVisitingMap.entrySet()) {
            BodyDeclarationPair bdp = item.getKey();
            int value = item.getValue();
            BodyDeclaration bd = bdp.getBodyDeclaration();
            if (!(bd instanceof TypeDeclaration)) {
                switch (value) {
                    case PreprocessedTempData.BODY_DIFFERENT_RETAIN:
                    case PreprocessedTempData.BODY_FATHERNODE_REMOVE:
                        break;
                    case PreprocessedTempData.BODY_INITIALIZED_VALUE:
                        this.preprocessedData.addBodiesDeleted(bdp);
                        preprocessedTempData.addToSrcRemoveList(bd);
                        break;
                    case PreprocessedTempData.BODY_SAME_REMOVE:
                        preprocessedTempData.addToSrcRemoveList(bd);
                        break;
                }
            }
        }
    }

    public PreprocessedData getPreprocessedData() {
        return preprocessedData;
    }

    public void undeleteSignatureChange() {
        List<BodyDeclarationPair> addTmp = new ArrayList<>();
        for (BodyDeclarationPair bdpAdd : preprocessedData.getmBodiesAdded()) {
            if (bdpAdd.getBodyDeclaration() instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) bdpAdd.getBodyDeclaration();
                String methodName = md.getName().toString();
                List<BodyDeclarationPair> bdpDeleteList = new ArrayList<>();
                for (BodyDeclarationPair bdpDelete : preprocessedData.getmBodiesDeleted()) {
                    if (bdpDelete.getBodyDeclaration() instanceof MethodDeclaration) {
                        MethodDeclaration md2 = (MethodDeclaration) bdpDelete.getBodyDeclaration();
                        String methodName2 = md2.getName().toString();
                        if (potentialMethodNameChange(methodName, methodName2)) {
                            bdpDeleteList.add(bdpDelete);
                        }
                    }
                }
                if (bdpDeleteList.size() > 0) {
                    //remove的时候可能会有hashcode相同但是一个是在内部类的情况，但是这种情况很少见，所以暂时先不考虑
                    preprocessedTempData.dstRemovalNodes.remove(bdpAdd.getBodyDeclaration());
                    addTmp.add(bdpAdd);
                    for (BodyDeclarationPair bdpTmp : bdpDeleteList) {
                        this.preprocessedTempData.srcRemovalNodes.remove(bdpTmp.getBodyDeclaration());
                        this.preprocessedData.getmBodiesDeleted().remove(bdpTmp);
                    }
                }
            }

        }
        for (BodyDeclarationPair tmp : addTmp) {
            this.preprocessedData.getmBodiesAdded().remove(tmp);
        }
    }

    public boolean potentialMethodNameChange(String name1, String name2) {
        if (name1.length() == 0) return false;
        String tmp;
        if (name1.length() > name2.length()) {
            tmp = name1;
            name1 = name2;
            name2 = tmp;
        }
        int i;
        for (i = 0; i < name1.length(); i++) {
            char ch1 = name1.charAt(i);
            char ch2 = name2.charAt(i);
            if (ch1 != ch2) {
                break;
            }
        }
        double ii = (i * 1.0) / name1.length();
        if (ii > 0.7) {
            return true;
        }
        return false;
    }


}