/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo360.replugin.model;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.qihoo360.replugin.helper.JSONHelper;
import com.qihoo360.replugin.helper.LogDebug;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Comparator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;


/**
 * 用来描述插件的描述信息。以Json来封装
 *
 * @author RePlugin Team
 */

public class PluginInfo implements Parcelable, Cloneable {

    private static final String TAG = "PluginInfo";

    /**
     * 表示一个尚未安装的"纯APK"插件，其path指向下载完成后APK所在位置
     */
    public static final int TYPE_NOT_INSTALL = 10;

    /**
     * 表示一个释放过的"纯APK"插件，其path指向释放后的那个APK包 <p>
     * <p>
     * 注意：此时可能还并未安装，仅仅是将APK拷贝到相应目录而已。例如，若是通过RePlugin.installDelayed时即为如此
     */
    public static final int TYPE_EXTRACTED = 11;

    /**
     * 表示为P-n已安装，其path指向释放后的那个Jar包（存在于app_plugins_v3，如clean-10-10-102.jar，一个APK）
     *
     * @deprecated 只用于旧的P-n插件，可能会废弃
     */
    public static final int TYPE_PN_INSTALLED = 1;

    /**
     * 内建插件
     *
     * @deprecated 只用于旧的P-n插件，可能会废弃
     */
    public static final int TYPE_BUILTIN = 2;

    /**
     * 表示为P-n还未安装，其path指向释放前的那个Jar包（在Files目录下，如p-n-clean.jar，有V5文件头）
     *
     * @deprecated 只用于旧的P-n插件，可能会废弃
     */
    public static final int TYPE_PN_JAR = 3;

    /**
     * 表示“不确定的框架版本号”，只有旧P-n插件才需要在Load期间得到框架版本号
     *
     * @deprecated 只用于旧的P-n插件，可能会废弃
     */
    public static final int FRAMEWORK_VERSION_UNKNOWN = 0;

    private JSONObject mJson;

    // 若插件需要更新，则会有此值
    private PluginInfo mPendingUpdate;
    private boolean mIsThisPendingUpdateInfo;

    // 若插件需要卸载，则会有此值
    private PluginInfo mPendingDelete;

    private PluginInfo(JSONObject jo) {
        initPluginInfo(jo);
    }

    private PluginInfo(String name, int low, int high, int ver) {
        mJson = new JSONObject();
        JSONHelper.putNoThrows(mJson, "name", name);
        JSONHelper.putNoThrows(mJson, "low", low);
        JSONHelper.putNoThrows(mJson, "high", high);
        JSONHelper.putNoThrows(mJson, "ver", ver);
    }

    private PluginInfo(String pkgName, String alias, int low, int high, int version, String path, int type) {
        // 如Low、High不正确，则给个默认值（等于应用的“最小支持协议版本”）

        mJson = new JSONObject();
        JSONHelper.putNoThrows(mJson, "pkgname", pkgName);
        JSONHelper.putNoThrows(mJson, "ali", alias);
        JSONHelper.putNoThrows(mJson, "name", makeName(pkgName, alias));
        JSONHelper.putNoThrows(mJson, "low", low);
        JSONHelper.putNoThrows(mJson, "high", high);

        setVersion(version);
        setPath(path);
        setType(type);
    }

    /**
     * 将PluginInfo对象应用到此对象中（克隆）
     *
     * @param pi PluginInfo对象
     */
    public PluginInfo(PluginInfo pi) {
        this.mJson = JSONHelper.cloneNoThrows(pi.mJson);
        this.mIsThisPendingUpdateInfo = pi.mIsThisPendingUpdateInfo;
        if (pi.mPendingUpdate != null) {
            this.mPendingUpdate = new PluginInfo(pi.mPendingUpdate);
        }
        if (pi.mPendingDelete != null) {
            this.mPendingDelete = new PluginInfo(pi.mPendingDelete);
        }
    }

    private void initPluginInfo(JSONObject jo) {
        mJson = jo;

        // 缓存“待更新”的插件信息
        JSONObject ujo = jo.optJSONObject("upinfo");
        if (ujo != null) {
            mPendingUpdate = new PluginInfo(ujo);
        }

        // 缓存“待卸载”的插件信息
        JSONObject djo = jo.optJSONObject("delinfo");
        if (djo != null) {
            mPendingDelete = new PluginInfo(djo);
        }
    }

    // 通过别名和包名来最终确认插件名
    // 注意：老插件会用到"name"字段，同时出于性能考虑，故必须写在Json中。见调用此方法的地方
    private String makeName(String pkgName, String alias) {
        if (!TextUtils.isEmpty(alias)) {
            return alias;
        }
        if (!TextUtils.isEmpty(pkgName)) {
            return pkgName;
        }
        return "";
    }

    /**
     * 通过插件APK的MetaData来初始化PluginInfo <p>
     * 注意：框架内部接口，外界请不要直接使用
     */
    public static PluginInfo parseFromPackageInfo(PackageInfo pi, String path) {
        ApplicationInfo ai = pi.applicationInfo;
        if (ai == null) {
            // 几乎不可能，但为保险起见，返回Null
            return null;
        }

        String pn = pi.packageName;
        String alias = null;
        int low = 0;
        int high = 0;
        int ver = 0;

        Bundle metaData = ai.metaData;

        // 优先读取MetaData中的内容（如有），并覆盖上面的默认值
        if (metaData != null) {
            // 获取插件别名（如有），如无则将"包名"当做插件名
            alias = metaData.getString("com.qihoo360.plugin.name");

            // 获取最低/最高协议版本（默认为应用的最小支持版本，以保证一定能在宿主中运行）
            low = metaData.getInt("com.qihoo360.plugin.version.low");
            high = metaData.getInt("com.qihoo360.plugin.version.high");

            // 获取插件的版本号。优先从metaData中读取，如无则使用插件的VersionCode
            ver = metaData.getInt("com.qihoo360.plugin.version.ver");
        }

        // 针对有问题的字段做除错处理


        PluginInfo pli = new PluginInfo(pn, alias, low, high, ver, path, PluginInfo.TYPE_NOT_INSTALL);

        // 获取插件的框架版本号
        pli.setFrameworkVersionByMeta(metaData);

        return pli;
    }

    /**
     * （框架内部接口）通过传入的JSON的字符串来创建PluginInfo对象 <p>
     * 注意：框架内部接口，外界请不要直接使用
     */
    public static PluginInfo parseFromJsonText(String joText) {
        JSONObject jo;
        try {
            jo = new JSONObject(joText);
        } catch (JSONException e) {
            if (LOG) {
                e.printStackTrace();
            }
            return null;
        }

        // 三个字段是必备的，其余均可
        if (jo.has("pkgname") && jo.has("type") && jo.has("ver")) {
            return new PluginInfo(jo);
        } else {
            return null;
        }
    }

    /**
     * 获取插件名，如果有别名，则返回别名，否则返回插件包名 <p>
     * （注意：旧插件"p-n"的"别名"就是插件名）
     */
    public String getName() {
        return mJson.optString("name");
    }

    /**
     * 获取插件包名
     */
    public String getPackageName() {
        return mJson.optString("pkgname");
    }

    /**
     * 获取插件别名
     */
    public String getAlias() {
        return mJson.optString("ali");
    }

    /**
     * 获取插件的版本
     */
    public int getVersion() {
        return mJson.optInt("ver");
    }

    /**
     * 获取最新的插件，目前所在的位置
     */
    public String getPath() {
        return mJson.optString("path");
    }

    /**
     * 设置最新的插件，目前所在的位置 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     */
    public void setPath(String path) {
        JSONHelper.putNoThrows(mJson, "path", path);
    }



    /**
     * 设置插件是否被使用过 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     *
     * @param used 插件是否被使用过
     */
    public void setIsUsed(boolean used) {
        JSONHelper.putNoThrows(mJson, "used", used);
    }

    /**
     * 获取Long型的，可用来对比的版本号
     */
    public long getVersionValue() {
        return mJson.optLong("verv");
    }

    /**
     * 插件的Dex是否已被优化（释放）了？
     *
     * @return 是否被使用过
     */

    /**
     * 获取APK存放的文件信息 <p>
     * 若为"纯APK"插件，则会位于app_p_a中；若为"p-n"插件，则会位于"app_plugins_v3"中 <p>
     *
     * @return Apk所在的File对象
     */
    public File getApkFile() {
        // 必须使用宿主的Context对象，防止出现“目录定位到插件内”的问题

        return  null;
    }

    /**
     * 获取Dex（优化后）生成时所在的目录 <p>
     * 若为"纯APK"插件，则会位于app_p_od中；若为"p-n"插件，则会位于"app_plugins_v3_odex"中 <p>
     * 注意：仅供框架内部使用
     *
     * @return 优化后Dex所在目录的File对象
     */
    public File getDexParentDir() {
        // 必须使用宿主的Context对象，防止出现“目录定位到插件内”的问题
      return null;
    }

    /**
     * 获取Dex（优化后）所在的文件信息 <p>
     * 若为"纯APK"插件，则会位于app_p_od中；若为"p-n"插件，则会位于"app_plugins_v3_odex"中 <p>
     * 注意：仅供框架内部使用
     *
     * @return 优化后Dex所在文件的File对象
     */


    /**
     * 根据类型来获取SO释放的路径 <p>
     * 若为"纯APK"插件，则会位于app_p_n中；若为"p-n"插件，则会位于"app_plugins_v3_libs"中 <p>
     * 注意：仅供框架内部使用
     *
     * @return SO释放路径所在的File对象
     */
    public File getNativeLibsDir() {
        // 必须使用宿主的Context对象，防止出现“目录定位到插件内”的问题
        return null;
    }

    /**
     * 获取插件当前所处的类型。详细见TYPE_XXX常量
     */
    public int getType() {
        return mJson.optInt("type");
    }

    /**
     * 设置插件当前所处的类型。详细见TYPE_XXX常量 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     */
    public void setType(int type) {
        JSONHelper.putNoThrows(mJson, "type", type);
    }

    /**
     * 是否已准备好了新版本？
     *
     * @return 是否已准备好
     */
    public boolean isNeedUpdate() {
        return mPendingUpdate != null;
    }

    /**
     * 获取将来要更新的插件的信息，将会在下次启动时才能被使用
     *
     * @return 插件更新信息
     */
    public PluginInfo getPendingUpdate() {
        return mPendingUpdate;
    }

    /**
     * 设置插件的更新信息。此信息有可能等到下次才能被使用 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     *
     * @param info 插件的更新信息
     */
    public void setPendingUpdate(PluginInfo info) {
        mPendingUpdate = info;
        if (info != null) {
            JSONHelper.putNoThrows(mJson, "upinfo", info.getJSON());
        } else {
            mJson.remove("upinfo");
        }
    }

    /**
     * 是否需要删除插件？
     *
     * @return 是否需要卸载插件
     */
    public boolean isNeedUninstall() {
        return mPendingDelete != null;
    }

    /**
     * 获取将来要卸载的插件的信息，将会在下次启动时才能被使用
     *
     * @return 插件卸载信息
     */
    public PluginInfo getPendingDelete() {
        return mPendingDelete;
    }

    /**
     * 设置插件的卸载信息。此信息有可能等到下次才能被使用 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     *
     * @param info 插件的卸载信息
     */
    public void setPendingDelete(PluginInfo info) {
        mPendingDelete = info;
        if (info != null) {
            JSONHelper.putNoThrows(mJson, "delinfo", info.getJSON());
        } else {
            mJson.remove("delinfo");
        }
    }


    /**
     * 获取框架的版本号 <p>
     * 此版本号不同于“协议版本”。这直接关系到四大组件和其它模块的加载情况
     */
    public int getFrameworkVersion() {
        // 仅p-n插件在用
        // 之所以默认为FRAMEWORK_VERSION_UNKNOWN，是因为在这里还只是读取p-n文件头，框架版本需要在loadDex阶段获得
        return mJson.optInt("frm_ver", FRAMEWORK_VERSION_UNKNOWN);
    }

    /**
     * 设置框架的版本号 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     *
     * @param version 框架版本号
     */
    public void setFrameworkVersion(int version) {
        JSONHelper.putNoThrows(mJson, "frm_ver", version);
    }

    /**
     * 根据MetaData来设置框架版本号 <p>
     * 注意：若为“纯APK”方案所用，则修改后需调用PluginInfoList.save来保存，否则会无效
     *
     * @param meta MetaData数据
     */
    public void setFrameworkVersionByMeta(Bundle meta) {

    }

    /**
     * 获取JSON对象。仅内部使用
     */
    public JSONObject getJSON() {
        return mJson;
    }

    /**
     * 生成用于放入app_plugin_v3（app_p_n）等目录下的插件的文件名，其中：<p>
     * 1、“纯APK”方案：得到混淆后的文件名（规则见代码内容） <p>
     * 2、“旧p-n”和“内置插件”（暂定）方案：得到类似 shakeoff_10_10_103 这样的比较规范的文件名 <p>
     * 3、只获取文件名，其目录和扩展名仍需在外面定义
     *
     * @return 文件名（不含扩展名）
     */

    /**
     * 更新插件信息。通常是在安装完新插件后调用此方法 <p>
     * 只更新一些必要的方法，如插件版本、路径、时间等。插件名之类的不会被更新
     *
     * @param info 新版本插件信息
     */
    public void update(PluginInfo info) {
        // TODO low high
        setVersion(info.getVersion());
        setPath(info.getPath());
        setType(info.getType());
    }

    /**
     * 此PluginInfo是否是一个位于其它PluginInfo中的PendingUpdate？只在调用RePlugin.install方法才能看到 <p>
     * 注意：仅框架内部使用
     *
     * @return 是否是PendingUpdate的PluginInfo
     */
    public boolean isThisPendingUpdateInfo() {
        return mIsThisPendingUpdateInfo;
    }

    /**
     * 此PluginInfo是否是一个位于其它PluginInfo中的PendingUpdate？ <p>
     * 注意：仅框架内部使用
     */
    public void setIsThisPendingUpdateInfo(boolean updateInfo) {
        mIsThisPendingUpdateInfo = updateInfo;
    }

    static PluginInfo createByJO(JSONObject jo) {
        PluginInfo pi = new PluginInfo(jo);
        // 必须有包名或别名
        if (TextUtils.isEmpty(pi.getName())) {
            return null;
        }

        return pi;
    }

    private void setVersion(int version) {
        JSONHelper.putNoThrows(mJson, "ver", version);

    }

    // -------------------------
    // Parcelable and Cloneable
    // -------------------------

    public static final Creator<PluginInfo> CREATOR = new Creator<PluginInfo>() {

        @Override
        public PluginInfo createFromParcel(Parcel source) {
            return new PluginInfo(source);
        }

        @Override
        public PluginInfo[] newArray(int size) {
            return new PluginInfo[size];
        }
    };

    private PluginInfo(Parcel source) {
        JSONObject jo = null;
        String txt = null;
        try {
            txt = source.readString();
            jo = new JSONObject(txt);
        } catch (JSONException e) {
            if (LogDebug.LOG) {
                LogDebug.e(TAG, "PluginInfo: mJson error! s=" + txt, e);
            }
            jo = new JSONObject();
        }
        initPluginInfo(jo);
    }

    @Override
    public Object clone() {
        return new PluginInfo(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mJson.toString());
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();

        b.append("PInfo { ");
        b.append(" }");

        return b.toString();
    }


}
