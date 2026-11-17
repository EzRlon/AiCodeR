/* musl 的 remove() 无法删除目录（只调 unlink），JDK 的 java.io.File.delete()
   因此在 musl 容器里删目录恒失败。此库按 POSIX 语义重载 remove()：
   先按文件 unlink，失败再按目录 rmdir。 */
#include <unistd.h>

int remove(const char *path) {
    if (unlink(path) == 0)
        return 0;
    return rmdir(path);
}
