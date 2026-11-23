package io.github.tianli0.switchtether.ui.files

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.File
import com.adamglin.phosphoricons.regular.FileArchive
import com.adamglin.phosphoricons.regular.FileAudio
import com.adamglin.phosphoricons.regular.FileCode
import com.adamglin.phosphoricons.regular.FileDoc
import com.adamglin.phosphoricons.regular.FileImage
import com.adamglin.phosphoricons.regular.FileLock
import com.adamglin.phosphoricons.regular.FileMd
import com.adamglin.phosphoricons.regular.FilePdf
import com.adamglin.phosphoricons.regular.FilePpt
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.FileVideo
import com.adamglin.phosphoricons.regular.FileXls
import com.adamglin.phosphoricons.regular.Folder
import io.github.tianli0.switchtether.files.FileIcon

/** Phosphor (MIT) icon for a list entry's [FileIcon] family. */
fun fileIconVector(icon: FileIcon): ImageVector = when (icon) {
    FileIcon.FOLDER -> PhosphorIcons.Regular.Folder
    FileIcon.CODE -> PhosphorIcons.Regular.FileCode
    FileIcon.TEXT -> PhosphorIcons.Regular.FileText
    FileIcon.MARKDOWN -> PhosphorIcons.Regular.FileMd
    FileIcon.IMAGE -> PhosphorIcons.Regular.FileImage
    FileIcon.PDF -> PhosphorIcons.Regular.FilePdf
    FileIcon.VIDEO -> PhosphorIcons.Regular.FileVideo
    FileIcon.AUDIO -> PhosphorIcons.Regular.FileAudio
    FileIcon.ARCHIVE -> PhosphorIcons.Regular.FileArchive
    FileIcon.DOCUMENT -> PhosphorIcons.Regular.FileDoc
    FileIcon.SHEET -> PhosphorIcons.Regular.FileXls
    FileIcon.SLIDES -> PhosphorIcons.Regular.FilePpt
    FileIcon.LOCKED -> PhosphorIcons.Regular.FileLock
    FileIcon.OTHER -> PhosphorIcons.Regular.File
}
