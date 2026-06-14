package com.tumuyan.ncnn.realsr;

/**
 * 纯 Java（不依赖 Android 框架）的 SAF 目录处理工具。
 *
 * <p>目录批处理会把一个<b>真实文件系统路径</b>传给 native 程序执行，因此用户通过
 * {@code ACTION_OPEN_DOCUMENT_TREE} 选中的 tree URI 需要被映射成文件系统路径。
 *
 * <p>但是<b>合法性校验必须以“是否持有 SAF 授权”为准</b>，而不是对映射出来的字符串调用
 * {@code new File(path).exists()/.isDirectory()} —— 后者对 SD 卡、非 primary 存储、
 * 厂商文档提供器、以及只能拿到 {@code content://} 的目录都会误判为非法路径，从而导致
 * 开始按钮无法点亮、或启动前被拦截。
 *
 * <p>把这部分逻辑抽到一个不依赖 Android 的类里，是为了能在 JVM 上用普通单元测试覆盖，
 * 这也是历史缺陷的根因所在。
 */
public final class SafDirectoryResolver {

    private SafDirectoryResolver() {
    }

    /** externalstorage 文档提供器对主存储卷使用的卷名。 */
    static final String PRIMARY = "primary";
    /** 下载目录等提供器返回的“原始路径”前缀，其后紧跟一个绝对路径。 */
    static final String RAW_PREFIX = "raw:";
    /** 非 primary 卷（SD 卡 / U 盘）的挂载根。 */
    static final String SECONDARY_STORAGE_ROOT = "/storage/";

    /**
     * 把 tree document id（即 {@code DocumentsContract.getTreeDocumentId(uri)} 的返回值）
     * 映射为真实文件系统路径；无法映射时返回 {@code null}。
     *
     * <p>支持的形式：
     * <ul>
     *   <li>{@code "primary:DCIM/RealSR"} → {@code <primaryStorageRoot>/DCIM/RealSR}</li>
     *   <li>{@code "primary:"}（卷根） → {@code <primaryStorageRoot>}</li>
     *   <li>{@code "1A2B-3C4D:Pictures"} → {@code /storage/1A2B-3C4D/Pictures}</li>
     *   <li>{@code "raw:/storage/emulated/0/x"} → {@code /storage/emulated/0/x}</li>
     * </ul>
     * 不含 {@code ':'} 分隔（例如部分厂商提供器或纯 content 目录）时返回 {@code null}，
     * 交由调用方在启动阶段给出明确提示，而不是把它误判成非法路径直接禁用按钮。
     *
     * @param treeDocumentId     tree document id，可能为 {@code null}
     * @param primaryStorageRoot {@code Environment.getExternalStorageDirectory().getAbsolutePath()}
     * @return 真实文件系统路径，或 {@code null}
     */
    public static String resolveFilesystemPath(String treeDocumentId, String primaryStorageRoot) {
        if (treeDocumentId == null) {
            return null;
        }
        String docId = treeDocumentId.trim();
        if (docId.isEmpty()) {
            return null;
        }

        // "raw:/absolute/path" —— 本身就是文件系统路径。
        if (docId.startsWith(RAW_PREFIX)) {
            String raw = docId.substring(RAW_PREFIX.length());
            return raw.isEmpty() ? null : normalize(raw);
        }

        int colon = docId.indexOf(':');
        if (colon < 0) {
            // 没有 "卷:相对路径" 结构，无法映射为文件系统路径。
            return null;
        }

        String volume = docId.substring(0, colon);
        String relPath = docId.substring(colon + 1);

        String root;
        if (PRIMARY.equals(volume)) {
            if (primaryStorageRoot == null || primaryStorageRoot.isEmpty()) {
                return null;
            }
            root = stripTrailingSlash(primaryStorageRoot);
        } else if (!volume.isEmpty()) {
            // 非 primary 卷（SD 卡 / U 盘）：/storage/<volume>/...
            root = SECONDARY_STORAGE_ROOT + volume;
        } else {
            return null;
        }

        if (relPath.isEmpty()) {
            return root;
        }
        return normalize(root + "/" + relPath);
    }

    /**
     * 取目录的叶子名（最后一级目录名），用于“自动输出目录”命名。
     * 当无法从 tree document id 推断时返回 {@code ""}，由调用方回退到其他来源。
     *
     * @param treeDocumentId tree document id，可能为 {@code null}
     * @return 叶子目录名，或空字符串
     */
    public static String leafName(String treeDocumentId) {
        if (treeDocumentId == null) {
            return "";
        }
        String docId = treeDocumentId.trim();
        if (docId.isEmpty()) {
            return "";
        }

        String pathPart;
        if (docId.startsWith(RAW_PREFIX)) {
            pathPart = docId.substring(RAW_PREFIX.length());
        } else {
            int colon = docId.indexOf(':');
            pathPart = (colon < 0) ? docId : docId.substring(colon + 1);
        }
        pathPart = stripTrailingSlash(pathPart.trim());
        if (pathPart.isEmpty()) {
            return "";
        }
        int slash = pathPart.lastIndexOf('/');
        return (slash < 0) ? pathPart : pathPart.substring(slash + 1);
    }

    /**
     * 输入目录是否合法：持有 SAF tree 授权即视为合法；或者（手动输入的路径）确实指向一个已存在目录。
     *
     * <p>关键修复点：只要持有 SAF 授权就足够，<b>不得</b>再要求对映射出来的路径调用
     * {@code File.exists()/.isDirectory()} —— 这正是对 SD 卡 / 非 primary 存储 / 厂商提供器
     * 误判的根因。
     *
     * @param hasTreeGrant           是否持有该输入目录的 SAF tree 授权
     * @param manualPathIsExistingDir 手动输入路径是否指向已存在目录（仅在无 SAF 授权时参考）
     */
    public static boolean isInputDirValid(boolean hasTreeGrant, boolean manualPathIsExistingDir) {
        return hasTreeGrant || manualPathIsExistingDir;
    }

    /**
     * 输出目录是否合法：持有 SAF tree 授权即视为合法；或者手动指定了非空目标路径
     * （输出目录允许尚不存在，启动时再创建）。
     *
     * @param hasTreeGrant        是否持有该输出目录的 SAF tree 授权
     * @param manualPathNonEmpty  手动输入的目标路径是否非空（仅在无 SAF 授权时参考）
     */
    public static boolean isOutputDirValid(boolean hasTreeGrant, boolean manualPathNonEmpty) {
        return hasTreeGrant || manualPathNonEmpty;
    }

    /** 仅当输入、输出、可运行模型三者都就绪时才允许开始。 */
    public static boolean canStart(boolean inputValid, boolean outputValid, boolean modelAvailable) {
        return inputValid && outputValid && modelAvailable;
    }

    private static String normalize(String path) {
        // 合并多余的连续斜杠（保留单个前导斜杠），并去掉结尾斜杠。
        String collapsed = path.replaceAll("/{2,}", "/");
        return stripTrailingSlash(collapsed);
    }

    private static String stripTrailingSlash(String path) {
        if (path == null) {
            return null;
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
