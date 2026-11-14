package io.github.tianli0.switchtether.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.ArrowDown
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import com.adamglin.phosphoricons.regular.Brain
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.ClipboardText
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.DotsThreeVertical
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Eye
import com.adamglin.phosphoricons.regular.EyeSlash
import com.adamglin.phosphoricons.regular.FilePlus
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.FolderPlus
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.ListChecks
import com.adamglin.phosphoricons.regular.Lock
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Notepad
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Plugs
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.PlusCircle
import com.adamglin.phosphoricons.regular.PuzzlePiece
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.PushPinSlash
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.Robot
import com.adamglin.phosphoricons.regular.Stack
import com.adamglin.phosphoricons.regular.StopCircle
import com.adamglin.phosphoricons.regular.TerminalWindow
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.UploadSimple
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.Wrench
import com.adamglin.phosphoricons.fill.ChatsCircle as ChatsCircleFill
import com.adamglin.phosphoricons.fill.FolderOpen as FolderOpenFill
import com.adamglin.phosphoricons.fill.GearSix as GearSixFill
import com.adamglin.phosphoricons.fill.House as HouseFill
import com.adamglin.phosphoricons.fill.PaperPlaneRight as PaperPlaneRightFill
import com.adamglin.phosphoricons.fill.SlidersHorizontal as SlidersHorizontalFill
import com.adamglin.phosphoricons.regular.ChatsCircle
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.SlidersHorizontal

/**
 * The app's icon vocabulary. Every glyph is a Phosphor icon (phosphoricons.com, MIT; Compose port
 * `com.adamglin:phosphor-icon`): Regular weight everywhere, Fill weight for the selected state of
 * navigation items and for the send button. This object only names them; it draws nothing itself.
 * coui components that bring their own glyphs (switches, arrows, sheet close capsules) keep them.
 */
object AppIcons {
    // Navigation: Regular (unselected) / Fill (selected) pairs.
    val Home: ImageVector get() = PhosphorIcons.Regular.House
    val HomeSelected: ImageVector get() = PhosphorIcons.Fill.HouseFill
    val Sessions: ImageVector get() = PhosphorIcons.Regular.ChatsCircle
    val SessionsSelected: ImageVector get() = PhosphorIcons.Fill.ChatsCircleFill
    val Workspace: ImageVector get() = PhosphorIcons.Regular.FolderOpen
    val WorkspaceSelected: ImageVector get() = PhosphorIcons.Fill.FolderOpenFill
    val Manage: ImageVector get() = PhosphorIcons.Regular.SlidersHorizontal
    val ManageSelected: ImageVector get() = PhosphorIcons.Fill.SlidersHorizontalFill
    val Settings: ImageVector get() = PhosphorIcons.Regular.GearSix
    val SettingsSelected: ImageVector get() = PhosphorIcons.Fill.GearSixFill

    // Actions.
    val Back: ImageVector get() = PhosphorIcons.Regular.ArrowLeft
    val Refresh: ImageVector get() = PhosphorIcons.Regular.ArrowClockwise
    val Add: ImageVector get() = PhosphorIcons.Regular.Plus
    val AddCircle: ImageVector get() = PhosphorIcons.Regular.PlusCircle
    val Info: ImageVector get() = PhosphorIcons.Regular.Info
    val Send: ImageVector get() = PhosphorIcons.Fill.PaperPlaneRightFill
    val Interrupt: ImageVector get() = PhosphorIcons.Regular.StopCircle
    val More: ImageVector get() = PhosphorIcons.Regular.DotsThreeVertical
    val Copy: ImageVector get() = PhosphorIcons.Regular.Copy
    val Paste: ImageVector get() = PhosphorIcons.Regular.ClipboardText
    val Edit: ImageVector get() = PhosphorIcons.Regular.PencilSimple
    val Delete: ImageVector get() = PhosphorIcons.Regular.Trash
    val Download: ImageVector get() = PhosphorIcons.Regular.DownloadSimple
    val Upload: ImageVector get() = PhosphorIcons.Regular.UploadSimple
    val NewFile: ImageVector get() = PhosphorIcons.Regular.FilePlus
    val NewFolder: ImageVector get() = PhosphorIcons.Regular.FolderPlus
    val Show: ImageVector get() = PhosphorIcons.Regular.Eye
    val Hide: ImageVector get() = PhosphorIcons.Regular.EyeSlash
    val Terminal: ImageVector get() = PhosphorIcons.Regular.TerminalWindow
    val Pin: ImageVector get() = PhosphorIcons.Regular.PushPin
    val Unpin: ImageVector get() = PhosphorIcons.Regular.PushPinSlash
    val ArrowDown: ImageVector get() = PhosphorIcons.Regular.ArrowDown
    val Clock: ImageVector get() = PhosphorIcons.Regular.Clock

    // States.
    val Done: ImageVector get() = PhosphorIcons.Regular.Check
    val Lock: ImageVector get() = PhosphorIcons.Regular.Lock
    val Warning: ImageVector get() = PhosphorIcons.Regular.Warning

    // Content kinds.
    val File: ImageVector get() = PhosphorIcons.Regular.FileText
    val Thinking: ImageVector get() = PhosphorIcons.Regular.Brain
    val Steps: ImageVector get() = PhosphorIcons.Regular.Stack
    val Plan: ImageVector get() = PhosphorIcons.Regular.ListChecks
    val Question: ImageVector get() = PhosphorIcons.Regular.Question
    val SubAgent: ImageVector get() = PhosphorIcons.Regular.Robot
    val Search: ImageVector get() = PhosphorIcons.Regular.MagnifyingGlass
    val Web: ImageVector get() = PhosphorIcons.Regular.Globe
    val Mcp: ImageVector get() = PhosphorIcons.Regular.Plugs
    val Tool: ImageVector get() = PhosphorIcons.Regular.Wrench
    val Providers: ImageVector get() = PhosphorIcons.Regular.ArrowsLeftRight
    val Usage: ImageVector get() = PhosphorIcons.Regular.ChartBar
    val Skills: ImageVector get() = PhosphorIcons.Regular.PuzzlePiece
    val Prompts: ImageVector get() = PhosphorIcons.Regular.Notepad
}
