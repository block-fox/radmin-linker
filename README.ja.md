# Radmin Linker

![Radmin Linker](docs/radmin-linker-cover.png)

[English](README.md) · [简体中文](README.zh-CN.md) · [Tiếng Việt](README.vi.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · **日本語**

Radmin Linker は、Minecraft 内から Radmin VPN に接続し、管理できるクライアント側の Fabric MOD です。Minecraft **26.3、26.2、1.21.11、1.21.1** に対応しています。ゲームのバージョンに合う JAR を [GitHub Releases](https://github.com/block-fox/radmin-linker/releases) からダウンロードしてください。変更点は [更新履歴](CHANGELOG.md) に記載しています。

## インストールと連携

- **Radmin Linker 単体**で、ネットワーク、ユーザー、ブロック済みユーザー、設定の各タブを利用できます。Fabric API もインストールしてください。通常ビルドは Windows の公式サービスを使用します。`-core.jar` ビルドの自動モードでは、Windows で起動時に公式サービスが利用できる場合はそのサービスを使用し、それ以外の場合は内蔵コアを使用します。[内蔵コアのガイド（中国語）](docs/core-integration.md)を参照してください。
- **LanWorlds 単体**で、標準の LAN 一覧にルームアイコン、MOTD、アドレス、プレイヤー数、遅延、ブロックルールを追加できます。[LanWorlds](https://github.com/block-fox/LanWorlds) を参照してください。
- **両方を導入**すると、該当する LAN ルームで Radmin のユーザー、ネットワーク、接続情報を確認でき、ユーザーやネットワークのプロパティを開けます。両方とも同じ Minecraft バージョン用のビルドを使用してください。Radmin Linker 1.0.1 と LanWorlds 1.0.2 で連携できます。

RadminLAN からアップグレードする場合は、先に古い JAR を削除してください。新しい設定ファイルがない場合、起動時に `config/radminlan.json` を読み込み、設定を `config/radminlinker.json` に保存します。

## 主な機能

- ネットワークとユーザーに 8 種類の表示方式があり、並べ替え、絞り込み、複数選択ができます。ネットワークのメンバー一覧を開くと、そのネットワークで自動的に絞り込みます。
- 一括操作の成功件数と失敗件数を表示し、失敗した項目を再試行できます。自動更新中も操作結果の表示が残ります。
- 管理画面の上部でユーザー名を変更し、VPN のオン・オフを切り替えられます。VPN をオフにする際は確認が表示されます。
- Windows サービスの制御パイプから、ネットワーク、ユーザー、測定した遅延を取得します。CLI を設定すると、照会の代替手段として使えます。
- オンライン状態をアイコンで表示し、カーソルを重ねると詳細を確認できます。自動更新を有効にすると、オンライン状態と遅延の通知が 2 ゲームティックごとに画面に反映されます。ネットワークとメンバー情報の全件照会は 1〜60 秒間隔で設定できます。遅延は測定値を取得するまで不明と表示され、その値は 60 秒で期限切れになります。
- 小さいウィンドウでも一覧、設定、プロパティをスクロールできます。設定は Mod Menu やマルチプレイ画面から開けます。

## 開発

Minecraft 26.2 と 26.3 はルートのソースを共有し、JDK 25 を使用します。1.21.11 と 1.21.1 は JDK 21 を使用し、バックエンドと設定のソースを共有します。対象バージョンのスクリプトで開発用クライアントを起動してください。

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

ビルドには対応する `scripts/build-*.ps1` を使用します。成果物は `versions/<minecraft-version>/build/` に出力されます。ビルドやバージョンの切り替えの前に、開発用クライアントを終了してください。依存関係がキャッシュされていれば `-Online` は省略できます。通常のチェックアウトでは、26.3 用の任意のローカル実行環境に代えて公開されている依存関係を使用してください。

```powershell
./gradlew.bat classes -Puse_local_runtime=false
```

対象バージョンに合う JDK で回帰チェックを実行します。26.2 では `versions/26.2/gradle.properties` の設定も `-P` 引数で渡します。

```powershell
./gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
```

入力、設定の移行、絞り込み、レイアウト、一括操作の部分的な失敗、CLI フォールバック、IPv6 の照合、テレメトリを検証します。Radmin VPN がインストール済みなら、`nativeTelemetrySmoke` で初回の読み取りと、その後の購読更新を確認できます。中国語の資料に [テレメトリの解説](docs/telemetry-protocol.md)、[LanWorlds 連携ガイド](docs/lanworlds-integration.md)、[開発ガイド](docs/development.md)があります。
