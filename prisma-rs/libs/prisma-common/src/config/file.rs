use super::ConnectionLimit;

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FileConfig {
    pub connector: String,

    #[serde(default)]
    pub test_mode: bool,

    pub database: Option<String>,
    pub connection_limit: Option<u32>,
    pub pooled: Option<bool>,
    pub schema: Option<String>,
    pub management_schema: Option<String>,

    migrations: Option<bool>,
    active: Option<bool>,
}

impl ConnectionLimit for FileConfig {
    fn connection_limit(&self) -> Option<u32> {
        self.connection_limit
    }

    fn pooled(&self) -> Option<bool> {
        self.pooled
    }
}
