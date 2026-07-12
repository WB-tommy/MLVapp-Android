package fm.magiclantern.forum.features.export

import fm.magiclantern.forum.features.export.model.ExportOptions
import fm.magiclantern.forum.features.export.model.ProgressListener
import fm.magiclantern.forum.nativeInterface.NativeLib

interface NativeExportEngine {
    fun prepare() = Unit

    fun export(
        memSize: Long,
        cpuCores: Int,
        clipFds: IntArray,
        options: ExportOptions,
        progressListener: ProgressListener,
        fileProvider: ExportFdProvider?
    )

    fun cancel()
}

object NativeLibExportEngine : NativeExportEngine {
    override fun prepare() {
        NativeLib.prepareExport()
    }

    override fun export(
        memSize: Long,
        cpuCores: Int,
        clipFds: IntArray,
        options: ExportOptions,
        progressListener: ProgressListener,
        fileProvider: ExportFdProvider?
    ) {
        NativeLib.exportHandler(
            memSize = memSize,
            cpuCores = cpuCores,
            clipFds = clipFds,
            options = options,
            progressListener = progressListener,
            fileProvider = fileProvider
        )
    }

    override fun cancel() {
        NativeLib.cancelExport()
    }
}
