/**
 * 設定ファイル( .kts スクリプト)
 */
AppConfig(
    // このアプリのHTTPサーバの待機アドレスとポート
    listenAddr = "localhost",
    listenPort = 8000,

    // nitterサーバのURLとホスト名
    nitterUrl = "https://nitter.xxx.com",
    nitterVirtualHost = "nitter.xxx.com",

    // nitterサーバに認証をかけているなら指定する
    // nitterBasicAuth = "user:pass",

    // DB初期化時にOPMLファイルを読んでDBをウォームアップする
    // subscriptions = "subscriptions.xml",

    // 起動時にプロセスIDをファイルに出力する
    // pidFile: String? = "nitterFilter2.pid",

    // SQLITE3 データベースのファイルパス
    // sqliteFile: String = "nitterFilter2.db",
)
