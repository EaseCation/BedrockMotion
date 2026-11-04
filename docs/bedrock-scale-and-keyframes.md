# 圣符 Boss 冲击波穿帮：原生缩放与关键帧语义

调查日期：2026-10-07。相关事项：[#726](https://mobius.easecation.net/issues/726)。

本轮只修改 NeoForge mod 消费的 BedrockMotion 动画库。CodeFunCore 原始资源、ViaProxy/ViaBedrock、Blockbench 和游戏二进制均作为只读参考；测试资源是原 JSON 的快照或通道摘录，不作为资源包修补。

## 可复现根因

`easecation:rl_ghosarea_boss_lock` 的背后冲击波是第一套几何中的 `root_effect`，挂在 `root_body` 下，包含一张尺寸为 `[0,15,55]` 的特效面。它不是粒子。`idle`、`move`、`attack_1`、`attack_2`、`attack_range` 等动画都显式把此骨骼缩放设为 0。

实体并列运行 move、hit、skill、unbalanced 四个控制器。move 的 idle/move 离开时有 0.2 秒淡出，skill 的攻击动画也写入 `root_effect.scale = 0`。旧引擎采用加法：

```text
S = 1 + Σ((s_i - 1) * w_i)
```

待机隐藏动画淡出到权重 0.75、攻击动画权重为 1 时，旧结果为 `1 - 0.75 - 1 = -0.75`。负缩放重新产生面积，因而出现无关技能开始时的短暂冲击波。真实控制器与动画快照在旧源码回归中得到该数值。

基岩原生采用逐层乘法（逐轴）：

```text
S_next = S_current * (1 + (s_animation - 1) * w_animation)
```

上述情况结果为 `0.25 * 0 = 0`。修复不对缩放做统一限幅，保留作者有意设置的负缩放及权重大于 1 时的原生运算。

## 原生证据

样本是网易开发者版 Android ARM64 主库中的基岩骨骼动画路径，不是网易脚本模拟。未声称已经动态验证国际版客户端所有版本。

| 样本 | SHA-256 |
|---|---|
| 3.10 | `e6d624173d5f417ab2149eb52b51c21a1a16a941b61493265b292894f60eaa69` |
| 3.9 | `a0f5332d443f20063cc0adce5cf935597cccf6c790ea6a9a7e79ec8a067b72f3` |

下列地址都是 ELF 相对虚拟地址，不能直接当运行期绝对地址。

| 原生路径 | 3.10 | 3.9 交叉核对 |
|---|---|---|
| ActorSkeletalAnimationPlayer 虚表 | `0x11fd0048` | `0x129a6680` |
| applyToPose（虚表 +0x10） | `0xc7b8988` | `0xd1d9128` |
| 遍历骨骼/通道 | `0xc7b852c` | `0xd1d8ccc` |
| 关键帧选择与插值 | `0xc7b6dc0` | `0xd1d7594` |
| 常量变换叠加 | `0xc7b62f0` | `0xd1d6ac4` |
| 表达式变换叠加 | `0xc7b5118` | 本轮未单独定位 |

定位由 `28ActorSkeletalAnimationPlayer` 的 RTTI→虚表→+0x10→通道调用链完成。3.10 常量变换在 `0xc7b636c` 判断通道类型 2，读写 pose 的 `+0x88/+0x90` 三轴缩放；`0xc7b6398..0xc7b63ac` 实现 `S + S*(s*w-w)`。表达式路径 `0xc7b52b8..0xc7b5364` 同样实现乘法。3.9 的对应指令结构与运算一致。

## 同类关键帧错误

本轮一并修复以下共享采样逻辑，因此也适用于旋转和位置关键帧：

1. **默认插值错误。** 原来的数组关键帧和未声明 `lerp_mode` 的对象关键帧默认为 cubic；现在默认为 linear。原生 schema 注册 `linear→0`、`catmullrom→1`，注册回调 `0xc875300` 写入关键帧 `+0x20`，采样器在 `0xc7b704c` 用它分流。默认 cubic 会在连续两个零值后接非零值时制造负值，例如 `[0,0,1]` 的第一段中点被旧引擎算成 `-0.0625`。
2. **跳变点选边错误。** 首关键帧前取 `pre`，精确到达关键帧时取 `post`，末关键帧之后继续取最后的 `post`。原生 `0xc7b6df4..0xc7b6e0c` 用严格 `<` 选择前后侧；`0xc7b6f70..0xc7b6f78` 在相等时将区间起点改为该关键帧。旧实现会在首帧前提前显示 post，以及在跳变点/末帧后继续使用 pre。
3. **插值方式影响错误区间。** 原生在 `0xc7b7044..0xc7b7060` 读取区间起点的模式。JE 旧代码让任一端 cubic 都启用 cubic，甚至让下一关键帧的 step 提前影响上一段。现在以起点为准。

Blockbench `js/animations/timeline_animators.js:displayScale` 同样乘以 `1+(s-1)*w`，但用 `0.00001` 替代零；本轮保留原生精确零。Blockbench 的关键帧默认是 linear，但预览会在区间任一端 cubic 时采用 cubic，与上面的原生分支不同。此次以原生为准，没有复制这项预览规则。

## 扩展资源检查

对 `ecpacks/ec_rl/animations` 扫描发现：

- 113 个直接写零缩放的通道，分布在锁 Boss、巴比伦、巴比伦分身、藤墙、机关等 5 个动画文件。
- 617 个缩放 pre/post 跳变，落在 300 个通道、8 个文件中，包括锁 Boss、巴比伦、巴比伦分身、藤墙、恶化之眼、两套木灵和巴比伦矛阵。
- 将这 300 个原始缩放通道摘录为回归输入；其中 297 个没有显式 cubic 的通道，逐个在每个关键帧前、附近与后采样，对照独立线性 pre/post 参考计算。3 个显式 cubic 通道未混入线性参考，另有保留 cubic 的专门回归。

这些数量表示同类错误可能影响的资源，不代表每个实体都已经实机复现穿帮。

## 验证与边界

- 修复前首批 7 项回归中 6 项失败，包括旧 Boss 过渡的 `-0.75`、两隐藏层的 `-0.5`、默认 cubic 的 `-0.0625` 和首帧前错误的 1。
- 修复后 BedrockMotion 全量 **59 项测试通过**，包括 11 项本轮回归。
- 真实锁 Boss 四个控制器验证：从 idle 进入 variant 1/2/3/4/6/7/8，冲击波保持隐藏；variant 5 冲刺末段仍出现 `[1,1.4,1.7]`，恢复后隐藏。
- VBU **22 项相关回归通过**（移动、玩家状态、实体 query、模型/附加物坐标），`:assemble` 构建成功。没有运行或声称全量 VBU 测试通过。
- 2026-10-08 用户授权交付到 BedrockMotion `main`，并维护 NeoForgeWorkspace `master` 的子模块指针；最终提交见 Git 历史与 Workspace gitlink。本轮未部署或完成修改后的实机画面验收。

原生普通状态淡出/淡入也会逐层计算缩放。例如未启用 shortest path 的同一控制器中，两个 `scale=0` 动画分别权重 0.5，原生结果为 0.25，而非 0。`ActorAnimationControllerPlayer` 的 `0xc7bdbf8..0xc7bdc4c` 分别以两个权重调用同一 pose 的状态播放器，另一个 shortest-path 分支则在两个 pose 上计算。本轮保留原生普通分支的乘法结果，没有增加“发现隐藏关键帧就强制隐藏”的特殊规则。快速连续状态切换、多级控制器和 shortest-path 的完整语义不据此宣称全部对齐。

此外，检查中确认引擎的 `override_previous_animation` 仍被实现为首次启动时全模型 reset；原生 `0xc7b825c` 在每次 applyToPose 前重置当前动画涉及的骨骼，具有不同作用域与时机。本轮圣符动画文件没有使用该字段，未将此独立兼容问题混入冲击波修复。

原始反汇编、脚本、资源扫描和构建日志保存在：

```text
/Users/fangyizhou/Documents/coding/mcpelauncher-manifest/build-macos-arm64/molang-scale-analysis/
```

该目录不在 Git；移交分析资料时需单独复制。`inspect_pose.py` 接受可重复执行的 JSON 指令，`39` 参数选择 3.9 样本；其地址、vtable 和 disasm 输出只用于静态分析。
