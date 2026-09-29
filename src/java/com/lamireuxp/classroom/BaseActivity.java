package com.lamireuxp.classroom;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

/**
 * 所有界面的公共骨架 —— 主题应用与深浅切换的重建约定。
 *
 * 每个界面都要做三件一模一样的事，各抄一份就会越抄越偏（CourseActivity 就漏掉了
 * renderedDark 的跟踪，主题在别处被改过时回来自动重着色的闸是缺的）：
 *
 *  1. super.onCreate **之前** setTheme——状态栏 / 对话框的配色在这一刻定下；
 *  2. 回到前台时检查深浅是否被别处改过（设置页改的、或「跟随系统」在后台被系统
 *     切换），变了就地重着色；
 *  3. 跟随系统模式下系统切深浅时自己重绘（manifest 声明了 uiMode，系统不会替我们
 *     重建）。
 *
 * 子类只实现两个钩子：
 *  · onCreateUi(b) —— 建界面（此时主题已应用、窗口层已着色）；
 *  · onRebuildUi() —— 就地重建界面内容。窗口层重着色由 reapplyTheme() 统一处理，
 *    所以设置页改主题、顶栏开关切主题都能直接调 reapplyTheme()，效果一致。
 *
 * onResume 的约定：深浅变过 → reapplyTheme()（重建用的就是最新数据，不再单独
 * 刷新）；没变 → onResumed()（默认空，需要「回来刷一次数据」的子类覆写）。
 */
public abstract class BaseActivity extends Activity {

    /** 上次着色时的深浅状态——判断从别处回来要不要重新着色。 */
    private boolean renderedDark;

    @Override
    protected void onCreate(Bundle b) {
        // 必须在 super.onCreate 之前：应用主题资源（窗口背景 / 状态栏 / 对话框默认色）
        setTheme(Ui.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        renderedDark = Ui.isDark(this);
        Ui.applyWindowTheme(this);
        onCreateUi(b);
    }

    /** 建界面。此时主题已应用、窗口层（状态栏 / 导航栏）已着色。 */
    protected abstract void onCreateUi(Bundle b);

    /**
     * 就地重新着色并重建界面。窗口层（状态栏 / 导航栏 / 图标明暗）与深浅状态
     * 在这里统一处理，任何「主题变了」的入口（基类的 onResume / 系统切换，
     * 或子类里的设置项、顶栏开关）都调它，效果一致。
     */
    protected final void reapplyTheme() {
        renderedDark = Ui.isDark(this);
        Ui.applyWindowTheme(this);
        onRebuildUi();
    }

    /** 就地重建界面内容（换主题 / 跟随系统切换 / 设置项变更都会走到）。 */
    protected abstract void onRebuildUi();

    /** 回到前台且深浅没变时调用；要「回来刷一次数据」的子类覆写。 */
    protected void onResumed() {}

    /**
     * 系统切深浅时的重建是否暂缓。CourseActivity 录音中返回 true——重建会把录音
     * 面板顶掉；录音结束后的下一次重建自然换上新的深浅。
     */
    protected boolean deferThemeRebuild() { return false; }

    @Override
    protected void onResume() {
        super.onResume();
        if (renderedDark != Ui.isDark(this)) {
            reapplyTheme();
        } else {
            onResumed();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // uiMode 在 manifest 的 configChanges 里声明过，系统切深浅色不会自动重建
        if (Prefs.THEME_SYSTEM.equals(Prefs.themeMode(this)) && !deferThemeRebuild()) {
            reapplyTheme();
        }
    }
}
