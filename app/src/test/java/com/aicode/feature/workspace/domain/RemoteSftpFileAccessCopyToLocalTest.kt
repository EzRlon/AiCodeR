package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSftpFileAccessCopyToLocalTest {

    @Test
    fun copyToLocal_preservesRemoteFileNameInIsolatedCacheDirectory() {
        val sftp = mockk<SFTPClient>(relaxed = true)
        every { sftp.statExistence(any()) } returns mockk()

        val connection = mockk<RemoteSshConnection>()
        every { connection.config } returns RemoteConnectionConfig(
            host = "server-1",
            username = "root",
            auth = RemoteAuth.Password("pass"),
            remoteWorkspacePath = "/data/.../test/111"
        )
        every { connection.remoteHome } returns "/root"
        coEvery { connection.sftp() } returns sftp

        val workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.currentPath() } returns "/"

        val access = RemoteSftpFileAccess(connection, workspaceRepository)

        val localFile = access.copyToLocal("docs/screenshot.png")
        localFile.deleteOnExit()

        assertEquals("临时文件应保留远程原文件名", "screenshot.png", localFile.name)
        assertTrue("应落在 aicode_remote_ 前缀的独立缓存目录里", localFile.parentFile.name.startsWith("aicode_remote_"))
        coVerify {
            sftp.get("/data/.../test/111/docs/screenshot.png", localFile.absolutePath)
        }

        localFile.delete()
        localFile.parentFile.delete()
    }
}
