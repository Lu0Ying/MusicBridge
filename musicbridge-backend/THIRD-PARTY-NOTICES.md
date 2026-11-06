# 第三方依赖声明

本文件记录后端新增第三方依赖的版本、许可证与用途。仓库内不包含第三方源码，依赖通过 Maven 构建获取。

## net.jthink:jaudiotagger:3.0.1

- **用途**：只读解析音频容器/元数据中的时长（MP3/M4A/WAV/FLAC/OGG），不做标签写入。
- **许可证**：LGPL（GNU Lesser General Public License，POM 中 `licenses/license/name=LGPL`，链接指向 GNU Lesser General Public License v2.1）。
- **来源**：Maven Central，坐标 `net.jthink:jaudiotagger:3.0.1`。
- **接入方式**：封装在 `com.musicbridge.clips.JaudiotaggerAudioMetadataProbe` 内，业务层只依赖 `AudioMetadataProbe` 接口，不直接引用其类。
- **已知限制**：读取的是容器/元数据记录的时长，不是完整解码验证，不能保证识别专门伪造时长字段的文件。

新增依赖前需在本文件登记；许可证义务（LGPL 动态链接/可替换性说明）在分发前复核。
