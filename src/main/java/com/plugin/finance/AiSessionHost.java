package com.plugin.finance;

/** AI 会话页签的宿主：把会话面板挂到工具窗口里，页面切换/关闭由宿主负责 */
public interface AiSessionHost {

    void addAiSession(String key, String title, AiChatPanel panel);

    /** 会话仍挂在界面上时返回面板，页签已关闭返回 null */
    AiChatPanel findAiSession(String key);

    void selectAiSession(AiChatPanel panel);

    /** 关闭并销毁某个入口的会话（关闭页签、切换解读对象时使用） */
    void closeAiSession(String key);
}
