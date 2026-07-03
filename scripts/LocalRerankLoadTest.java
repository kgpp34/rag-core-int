import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

public class LocalRerankLoadTest {

    private static final String DEFAULT_URL = "http://172.31.73.27/jina_like_rerank/rerank";
    private static final String DEFAULT_TOKEN = "cffex-bhckgfzs31uadgek";
    private static final int DEFAULT_DURATION_SECONDS = 1;
    private static final int DEFAULT_MAX_CONCURRENT = 18;
    private static final int DEFAULT_MAX_CONNECTIONS = 64;
    private static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 32;

    private static final String QUERY = "国债期货临近交割月并进入交割月的合约保证金率变化是怎样的";
    private static final String MODEL = "bge_m3";

    private static final List<String> DOCS = List.of(
            """
            关于5年期国债期货最小变动价位和交易保证金有关事项的通知
            各会员单位：
            根据我所2015年2月27日修订并发布的5年期国债期货合约及其细则，自2015年3月16日结算时起，5年期国债期货所有挂牌合约的最小变动价位调整为0.005元。
            依据《中国金融期货交易所交易细则》第四十一条，在3月17日5年期国债期货合约交易中，若使用上一交易日收盘价作为前一成交价（cp）时，按5年期国债期货合约最小变动价位0.005元取值后作为前一成交价确定第一笔成交价。
            5年期国债期货新上市合约自2015年3月16日起交易保证金标准为合约价值的1.2%；
            交割月份前一个月下旬的前一交易日结算时起，交易保证金为合约价值的1.5%，交割月份第一个交易日的前一交易日结算时起，交易保证金为合约价值的2%。
            已上市合约的交易保证金仍按我所于2014年10月31日发布的《关于调整5年期国债期货交易保证金的通知》规定的标准执行。
            特此通知。
            中国金融期货交易所
            2015年3月10日
            """,
            """
            1
            30 年期国债期货合约
            合约标的 面值为 100 万元人民币、票面利率为 3%的
            名义超长期国债
            可交割国债 发行期限不高于 30 年，合约到期月份首日
            剩余期限不低于 25 年的记账式附息国债
            报价方式 百元净价报价
            最小变动价位 0.01 元
            合约月份 最近的三个季月（3 月、6 月、9 月、12 月
            中的最近三个月循环）
            交易时间 09:30 - 11:30，13:00 - 15:15
            最后交易日交易时间 09:30 - 11:30
            每日价格最大波动限制 上一交易日结算价的±3.5%
            最低交易保证金 合约价值的 3.5%
            最后交易日 合约到期月份的第二个星期五
            最后交割日 最后交易日后的第三个交易日
            交割方式 实物交割
            交易代码 TL
            上市交易所 中国金融期货交易所
            """,
            """
            三、交易保证金和涨跌停板幅度
            10年期国债期货各合约的交易保证金为合约价值的2%，交割月份前一个月下旬的前一交易日结算时起，交易保证金为合约价值的3%，交割月份第一个交易日的前一交易日结算时起，交易保证金为合约价值的4%。
            上市当日各合约的涨跌停板幅度为挂盘基准价的±4％。
            四、相关费用
            10年期国债期货合约的手续费标准暂定为每手3元，平今仓交易免收手续费，交割手续费标准为每手5元。
            交易所有权根据市场运行情况对手续费标准进行调整。
            """,
            """
            1
            2 年期国债期货合约
            合约标的
            面值为 200 万元人民币、票面利率为 3%的名
            义中短期国债
            可交割国债
            发行期限不高于 5 年，合约到期月份首日剩
            余期限为 1.5-2.25 年的记账式附息国债
            报价方式 百元净价报价
            最小变动价位 0.005 元
            合约月份
            最近的三个季月（3 月、6 月、9 月、12 月中
            的最近三个月循环）
            交易时间 9:30-11:30，13:00-15:15
            最后交易日
            交易时间
            9:30-11:30
            每日价格
            最大波动限制
            上一交易日结算价的±0.5%
            最低交易保证金 合约价值的 0.5%
            最后交易日 合约到期月份的第二个星期五
            最后交割日 最后交易日后的第三个交易日
            交割方式 实物交割
            交易代码 TS
            上市交易所 中国金融期货交易所
            """,
            """
            ## **业务目标**
            为了防范国债期货交割风险，国债交割月之前的二个交易日结算完成之前，由结算部向交易部提供已申报现券账户并通过审核的客户清单。
            对未在规定时间内申报国债托管账户或者托管账户未通过交易所审核的客户，其在国债期货临近交割月份合约上的持仓将按照客户合约超仓进行强平处理。
            对于交易客户，自交割月之前的一个交易日至合约最后交易日，系统将所有交易客户的国债期货临近交割合约持仓限额调整为0，并根据客户清单，将已申报现货账户且通过审核的投机客户的国债交割月合约持仓限额由0手调整为相应国债期货梯度限仓标准所规定的额度。
            对于已申请国债期货临近交割月份合约额度的套保、套利、做市商客户，该客户的国债期货产品临近交割月合约持仓限额为交易所审批的临近交割月合约套保、套利、做市商额度，若客户未申报国债现货托管账户，自交割月之前的一个交易日至合约最后交易日，系统将该客户的临近交割月合约持仓限额调整为0。
            若客户未申请临近交割月份合约额度，按照现有流程处理（默认限仓为0）。
            """,
            """
            贵单位/您在我公司开户从事国债期货交易， 2021年03月份国债期货交割临近，**根据《中国金融期货交易所国债期货合约交割细则》，在国债期货合约交割月份之前的二个交易日尚未通过国债托管账户审核的客户，自交割月份之前的一个交易日至最后交易日，在该国债期货交割月份合约的持仓应当为****0****。
            自交割月份之前的一个交易日起，交易所对未通过国债托管账户审核客户的交割月份合约持仓予以强行平仓**。
            请贵单位/您在仔细阅读并充分理解上述规定之后，于《国债期货合约交割风险提示确认书》上盖章/签字提交本公司。
            特此通知。
            期货公司（盖章） 年  月  日 |
            | --- |
            **附件2：**
            | 附件2 **国债期货合约交割风险提示确认回执**              （期货公司）:
            经贵公司告知，本单位/本人已认真阅读《国债期货合约交割风险提示通知》的全部内容，充分理解根据《中国金融期货交易所国债期货合约交割细则》，在国债期货合约交割月份之前的二个交易日尚未通过国债托管账户审核的客户，自交割月份之前的一个交易日至最后交易日，在该国债期货交割月份合约的持仓应当为0。
            自交割月份之前的一个交易日起，交易所对未通过国债托管账户审核客户的交割月份合约持仓予以强行平仓。
            本着风险自担的原则，本单位/本人将遵守上述相关规则。
            客户（盖章/签字）：
            年  月  日 |
            | --- |
            ### 通知样式
            | |
            | --- |
            | 关于提示国债期货交割相关事项的通知 **【#通知文号】** 各会员单位：
            现就2020年12月份5年期国债期货、10年期国债期货、2年期国债期货交割相关事项提示如下：
            """,
            """
            | 7 | 交割月合约潜在超仓提醒 | 701 | 国债期货 | 卖 | | 套保套利 | 某客户距离交割月额度生效日N个交易日，计算 先按交易编码（12位编码）在临近交割月合约上进行对冲，对冲后 C1=该客户在交割月合约上的卖持仓量 C2生成逻辑：
            同701买方向逻辑 ~~1、额度表中有数据时直接获取额度表数据；
            ~~ ~~2.1、额度表中无数据时生成默认额度，生成前先排除已申请（申请表中有数据或额度表中有数据）的客户+合约，这一部分数据也不计算报警，~~ ~~2.2、申请表额度表均无数据时生成默认额度，套利客户直接生成0额度，套保客户按实际持仓和持仓限额取小。
            ~~ | 同上 | ~~客户临近交割月合约XXXX卖持仓量为X手，超过了即将生效的临近交割月卖额度，为X手。
            ~~ 客户已（未）提交临近交割月合约额度申请，在XXXX合约上卖持仓XX手（已对冲），超过预计获得交割月合约卖方向额度XX手。
            | 属于风险提醒，触发后无需处理 |
            | 8 | 期现比例连续监测 | 801 | 产品组 | 不区分方向 | | 套保 套利 | 连续N（初始参数设置为5）个交易日，套保套利客户期现持仓比例超过1倍的（>1），5个交易日内最小持仓>1000手（参数设置） | 报警（只进行提示） | 客户在XX产品组上连续5个交易日（标识期现比例连续监测计算日期区间），期现持仓比例超过1倍。
            | 产品组同期现匹配设置产品组，不含合并匹配 |
            """,
            """
            1
            5 年期国债期货合约
            合约标的 面值为 100 万元人民币、票面利率为 3%
            的名义中期国债
            可交割国债
            发行期限不高于 7 年、合约到期月份首
            日剩余期限为 4-5.25 年的记账式附息国
            债
            报价方式 百元净价报价
            最小变动价位 0.005 元
            合约月份 最近的三个季月（3 月、6 月、9 月、12
            月中的最近三个月循环）
            交易时间 09:30-11:30， 13:00-15:15
            最后交易日
            """,
            """
            为加强防范交割风险，中金所国债期货将采用滚动交割、实物交割制度。
            滚动交割期：自交割月第一个交易日至最后交易日的前一个交易日，持有交割月合约的卖方主动提出交割申请，交易所按照一定规则选取买方进入交割。
            集中交割期：最后交易日闭市后，同一客户所持有的该交割月合约买卖持仓相对应部分自动平仓后，剩余未平仓交割合约自动进入交割程序
            ### **1、交割流程**
            * #### 交割模式
            交割模式分为【DVP模式】和【一般模式】；
            交割配对完成后，如果配对的卖方交券托管机构及买方收券托管机构都是中债登且买方有账户（意愿申报或事先已有有效账户）、卖方交券账户和买方收券账户不相同，交割模式为【DVP模式】，否则为【一般模式】。
            * #### 一般模式
            T日：买卖方交割意愿申报，交易所进行交割匹配：确定进入交割的买卖方，并收取交割手续费、调整交易持仓量为交割持仓量，完成卖方和买方交割配对；
            T+1日：交易所向托管机构发送卖方划入指令，即卖方债券过户到中金所账户的指令；
            T+2日：交易所进行卖方划入结果确认，收取买方交割货款，如有转托管，交易所向托管机构发送转托管指令；
            T+3日：若T+2日有转托管，交易所需进行转托管划转结果确认，向托管机构发送划至买方指令，即将之前卖方转入中金所账户的债券过户到买方账户的指令；
            T+4日：交易所进行划至买方结果确认，完成交割。
            * #### DVP模式
            T日：买卖方交割意愿申报，交易所确定进入交割的买卖方、收取交割手续费、调整交易持仓量为交割持仓量，完成卖方和买方交割配对；
            T+1日：无
            T+2日：交易所向托管机构发送DVP划转指令，即买卖方券款对付的指令；
            T+3日：交易所进行DVP划转结果确认，完成交割。
            """,
            """
            关于30年期国债期货合约上市交易有关事项的通知
            中金所发〔2023〕21号
            各会员单位：
            中国证监会已同意中国金融期货交易所30年期国债期货注册。
            现将30年期国债期货合约上市交易有关事项通知如下：
            一、上市交易时间
            30年期国债期货合约自2023年4月21日（星期五）起上市交易。
            二、上市交易合约和挂盘基准价
            30年期国债期货首批上市合约为2023年6月（TL2306）、2023年9月（TL2309）和2023年12月（TL2312）。
            各合约挂盘基准价由交易所在合约上市交易前一交易日公布。
            三、交易保证金和涨跌停板幅度
            30年期国债期货各合约的交易保证金为合约价值的3.5%，自交割月份之前的两个交易日结算时起，交易保证金标准为合约价值的5%。
            对2年期国债期货、5年期国债期货、10年期国债期货和30年期国债期货的跨品种双向持仓，按照交易保证金单边较大者收取交易保证金。
            上市当日各合约的涨跌停板幅度为挂盘基准价的±7%。
            四、相关费用
            30年期国债期货合约的手续费标准暂定为每手3元，平今仓交易免收手续费，交割手续费标准为每手5元，交割手续费至2023年12月31日止减半收取。
            交易所有权根据市场运行情况对手续费标准进行调整。
            五、交易指令每次最大下单数量
            30年期国债期货各合约限价指令每次最大下单数量为50手，市价指令每次最大下单数量为30手。
            六、可交割国债、转换因子及应计利息
            30年期国债期货各合约的可交割国债和转换因子由交易所在合约上市交易前公布。
            其转换因子计算公式如下：
            其中，r：30年期国债期货合约票面利率3%；
            x：交割月到下一付息月的月份数；
            n：剩余付息次数；
            c：可交割国债的票面利率；
            f：可交割国债每年的付息次数。
            计算结果四舍五入至小数点后4位。
            其应计利息的日计数基准为“实际天数/实际天数”，即每100元可交割国债的应计利息计算公式如下：
            应计利息=(可交割国债票面利率×100)/每年付息次数×(第二交割日-上一付息日)/当前付息周期实际天数
            计算结果四舍五入至小数点后7位。
            七、做市商持仓限额
            做市商所有月份30年期国债期货合约单边持仓限额为6000手，某一合约在交割月份之前的一个交易日起，单边持仓限额为1200手。
            30年期国债期货合约上市交易其他事项另行公布。
            各会员单位要认真做好30年期国债期货合约上市交易的各项准备工作，严格控制市场风险，确保30年期国债期货平稳推出和安全运行。
            特此通知。
            中国金融期货交易所
            2023年4月14日
            """,
            """
            关于国债期货交割业务有关事项的通知
            各会员单位：
            我所于2015年6月26日修订并发布了《中国金融期货交易所国债期货合约交割细则》，对国债期货交割业务进行调整。
            自2015年7月1日起，参与交割的客户应当事先通过会员向交易所申报国债托管账户。
            在交割月份之前的二个交易日尚未通过国债托管账户审核的客户，自交割月份之前的一个交易日至最后交易日，其在该国债期货交割月份合约的持仓应当为0手。
            自交割月份之前的一个交易日起，交易所按照《中国金融期货交易所风险控制管理办法》的相关规定，对未通过国债托管账户审核客户的交割月份合约持仓予以强行平仓。
            请各会员单位根据业务规则，做好国债托管账户申报和交割风险防范工作。
            特此通知。
            中国金融期货交易所
            2015年8月7日
            """,
            """
            — 2 —
            超过 30%的，按该品种汇总的非期货公司结算会员的总成交量、
            单边总持仓量。
            第五条 交易所发布的国债期货交割信息包括每日交割意向
            申报信息和合约交割信息等内容。
            交易所可以根据市场需要调整国债期货交割信息的公布频
            率与内容。
            第六条 每日交割意向申报信息包括下列主要内容：
            （一）意向交割国债信息：国债全称、到期日、票面利率、
            申报交割量；
            （二）所有结算会员的申报买方交割总量和申报卖方交割总
            量。
            第七条 国债期货合约交割信息包括下列主要内容：
            （一）交割数据统计：合约代码、交割量和交割金额；
            （二）交割国债信息：国债全称、到期日、票面利率、交割
            量、交割金额。
            第八条 本指引由交易所负责解释。
            第九条 本指引自 2023 年 4 月 19 日起实施。
            """,
            """
            — 2 —
            超过 30%的，按该品种汇总的非期货公司结算会员的总成交量、
            单边总持仓量。
            第五条 交易所发布的国债期货交割信息包括每日交割意向
            申报信息和合约交割信息等内容。
            交易所可以根据市场需要调整国债期货交割信息的公布频
            率与内容。
            第六条 每日交割意向申报信息包括下列主要内容：
            （一）意向交割国债信息：国债全称、到期日、票面利率、
            申报交割量；
            （二）所有结算会员的申报买方交割总量和申报卖方交割总
            量。
            第七条 国债期货合约交割信息包括下列主要内容：
            （一）交割数据统计：合约代码、交割量和交割金额；
            （二）交割国债信息：国债全称、到期日、票面利率、交割
            量、交割金额。
            第八条 本指引由交易所负责解释。
            第九条 本指引自 2023 年 4 月 19 日起实施。
            """,
            """
            | 交割系统 | 国债期货交割业务：涉及国债托管账户报备、保证金冻结、交割货款划转等调整。
            | 根据买卖双方交易编码将券款划拨至相应的国债托管账户和会员资金账户。
            | 非必备 | | | | | | |
            | 担保金系统 | 担保金季度变动计算 | 1.
            要点：经纪会员和自营会员，只交一份保证金，在计算时需要合并计算；
            2.
            问题：自营会员违约，担保金也可以动用吗？
            【结算部意见】结算担保金按结算会员主体收取，结算担保金系统仍按结算会员主体办理资金划转等业务。
            需要改造担保金分担逻辑，确定结算担保金分担权重时，需以结算会员为主体合并计算自营会员和代理会员的交易保证金和成交金额。
            结算担保金动用方面金额为自营会员和代理会员资金缺口的合计数。
            | 必备 | | 韩冲 | 修改结算担保金分担逻辑，确定结算担保金分担权重时，以结算会员为主体合并计算自营账户和代理账户的交易保证金和成交金额。
            | 建议方案：自营入会时，复用代理会员号的担保金资金账户、担保金银行账户，在计算季度分担变动时，将自营会员的成交持仓合并到代理会员一起计算。
            """,
            """
            | 发布时间 | 发布渠道 | 发布环境 |
            | --- | --- | --- |
            | 业务系统在交割月前一个月第一个交易日自动生成交割风险提示通知发送至通知管理平台草稿箱，结算部操作人员在通知管理平台，选择国债期货交割风险提示通知草稿，点击发送按钮 | 参与人平台 | 生产+仿真 |
            注：结算的通知仅发送至参与人平台，不发送官网
            仿真上目前无强平业务，按照业务要求也正常推送至草稿箱，是否发送由业务部门自行决定。
            ## 1.
            生产环境
            ### 通知变量
            | 参数名称 | 规则 |
            | --- | --- |
            | 交割月份 | 国债期货交割年月 例：2020年12月 |
            | 产品名称 | 国债期货交割产品号 包括：5年期国债期货、10年期国债期货、2年期国债期货（按照该顺序展示list） 产品名称list表示多个产品号，展示规则参照通用规则。
            存在新产品根据接口自动获取 |
            | 合约号 | 交割月份合约 合约号list表示多个合约号 |
            | 持仓限额 | 合约的持仓限额 持仓限额list表示多个合约的持仓限额，根据每个合约号的顺序，展示对应的持仓限额 |
            | 最后交易日 | 合约最后交易日，例如2020年3月12日 |
            | 交割月前一个交易日 | xx月xx日 如交割月为2021年3月，则该日期为2月26日 |
            | 交割月前两个交易日 | xx月xx日 如交割月为2021年3月，则该日期为2月25日 |
            | 通知发布日期 | 通知发布时的交易日 |
            ### 通知模板
            | |
            | --- |
            | 关于提示国债期货交割相关事项的通知 【#通知文号】 各会员单位：
            现就【#交割月份】【#产品名称list】交割相关事项提示如下：
            """,
            """
            1
            中国金融期货交易所
            30 年期国债期货合约交易细则
            （2023 年 4 月 14 日发布）
            第一章 总则
            第一条 为规范中国金融期货交易所（以下简称交易所）
            30 年期国债期货合约（以下简称本合约）交易行为，根据《中
            国金融期货交易所交易规则》及相关实施细则，制定本细则。
            第二条 交易所、会员、客户、期货保证金存管银行及
            期货市场其他参与者应当遵守本细则。
            第三条 本细则未规定的，按照交易所相关业务规则的
            规定执行。
            第二章 合约
            第四条 本合约的合约标的为面值为 100 万元人民币、
            票面利率为 3%的名义超长期国债。
            第五条 本合约的可交割国债为发行期限不高于 30 年、
            合约到期月份首日剩余期限不低于 25 年的记账式附息国债。
            第六条 本合约以每百元面值国债作为报价单位，以净
            价方式报价。
            净价方式是指以不含自然增长应计利息的价格
            2
            报价。
            第七条 本合约的最小变动价位为 0.01 元，合约交易报
            价为 0.01 元的整数倍。
            第八条 本合约的合约月份为最近的三个季月。
            季月是
            指 3 月、6 月、9 月、12 月。
            第九条 本合约的最后交易日为合约到期月份的第二
            个星期五。
            最后交易日为国家法定假日或者因异常情况等原
            因未交易的，以下一交易日为最后交易日。
            到期合约最后交易日的下一交易日，新的月份合约开始
            交易。
            第十条 本合约的交易代码为 TL。
            第三章 交易业务
            """
    );

    private static final AtomicInteger completedRequests = new AtomicInteger();
    private static final AtomicInteger successRequests = new AtomicInteger();
    private static final AtomicInteger failedRequests = new AtomicInteger();
    private static final AtomicInteger requestSeq = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        String requestBody = buildRequestBody(QUERY, DOCS, MODEL);

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnectionsPerRoute())
                .build();

        Semaphore permits = new Semaphore(config.maxConcurrent());
        Instant start = Instant.now();
        Instant deadline = start.plusSeconds(config.durationSeconds());

        System.out.printf(
                "开始压测 url=%s, duration=%ds, maxConcurrent=%d, documents=%d, bodyBytes=%d, httpClient=apache, maxConn=%d, maxConnPerRoute=%d%n",
                config.url(),
                config.durationSeconds(),
                config.maxConcurrent(),
                DOCS.size(),
                requestBody.getBytes(StandardCharsets.UTF_8).length,
                config.maxConnections(),
                config.maxConnectionsPerRoute()
        );

        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (Instant.now().isBefore(deadline)) {
                if (!permits.tryAcquire()) {
                    Thread.sleep(1);
                    continue;
                }

                int taskId = requestSeq.incrementAndGet();
                executor.submit(() -> {
                    try {
                        task(client, config, requestBody, taskId);
                    } finally {
                        completedRequests.incrementAndGet();
                        permits.release();
                    }
                });
            }
        }

        double seconds = Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0d;
        double rps = completedRequests.get() / seconds;
        System.out.printf(
                "共完成%d个请求，成功%d个，失败%d个，耗时%.3f秒, 平均rps%.3f, 预计一小时可完成%.0f条请求%n",
                completedRequests.get(),
                successRequests.get(),
                failedRequests.get(),
                seconds,
                rps,
                rps * 3600
        );
    }

    private static void task(CloseableHttpClient client, Config config, String requestBody, int taskId) {
        long start = System.nanoTime();
        System.out.printf("Task %d is starting%n", taskId);

        try {
            HttpPost request = new HttpPost(config.url());
            request.setHeader("Authorization", "Bearer " + config.token());
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            ClassicHttpResponse response = client.executeOpen(null, request, null);
            String responseBody;
            try {
                HttpEntity entity = response.getEntity();
                responseBody = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            } finally {
                response.close();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            int statusCode = response.getCode();
            if (statusCode >= 200 && statusCode < 300) {
                successRequests.incrementAndGet();
            } else {
                failedRequests.incrementAndGet();
            }
            System.out.printf(
                    "Task %d is completed, status=%d, elapsedMs=%d, responseBytes=%d%n",
                    taskId,
                    statusCode,
                    elapsedMs,
                    responseBody.getBytes(StandardCharsets.UTF_8).length
            );
            if (config.printResponse()) {
                System.out.println(responseBody);
            }
        } catch (Exception ex) {
            failedRequests.incrementAndGet();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            System.out.printf("Task %d error, elapsedMs=%d, error=%s%n", taskId, elapsedMs, ex);
        }
    }

    private static String buildRequestBody(String query, List<String> documents, String model) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendJsonField(sb, "query", query);
        sb.append(',');
        sb.append("\"documents\":[");
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendJsonString(sb, documents.get(i));
        }
        sb.append("],");
        appendJsonField(sb, "model", model);
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append("\\u%04x".formatted((int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private record Config(
            String url,
            String token,
            int durationSeconds,
            int maxConcurrent,
            int requestTimeoutSeconds,
            int maxConnections,
            int maxConnectionsPerRoute,
            boolean printResponse
    ) {
        static Config parse(String[] args) {
            String url = DEFAULT_URL;
            String token = DEFAULT_TOKEN;
            int durationSeconds = DEFAULT_DURATION_SECONDS;
            int maxConcurrent = DEFAULT_MAX_CONCURRENT;
            int requestTimeoutSeconds = 60;
            int maxConnections = DEFAULT_MAX_CONNECTIONS;
            int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
            boolean printResponse = false;

            for (String arg : args) {
                if (arg.startsWith("--url=")) {
                    url = arg.substring("--url=".length());
                } else if (arg.startsWith("--token=")) {
                    token = arg.substring("--token=".length());
                } else if (arg.startsWith("--duration=")) {
                    durationSeconds = Integer.parseInt(arg.substring("--duration=".length()));
                } else if (arg.startsWith("--concurrency=")) {
                    maxConcurrent = Integer.parseInt(arg.substring("--concurrency=".length()));
                } else if (arg.startsWith("--timeout=")) {
                    requestTimeoutSeconds = Integer.parseInt(arg.substring("--timeout=".length()));
                } else if (arg.startsWith("--max-connections=")) {
                    maxConnections = Integer.parseInt(arg.substring("--max-connections=".length()));
                } else if (arg.startsWith("--max-connections-per-route=")) {
                    maxConnectionsPerRoute = Integer.parseInt(arg.substring("--max-connections-per-route=".length()));
                } else if ("--print-response".equals(arg)) {
                    printResponse = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (durationSeconds <= 0) {
                throw new IllegalArgumentException("--duration must be positive");
            }
            if (maxConcurrent <= 0) {
                throw new IllegalArgumentException("--concurrency must be positive");
            }
            if (requestTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("--timeout must be positive");
            }
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("--max-connections must be positive");
            }
            if (maxConnectionsPerRoute <= 0) {
                throw new IllegalArgumentException("--max-connections-per-route must be positive");
            }
            return new Config(
                    url,
                    token,
                    durationSeconds,
                    maxConcurrent,
                    requestTimeoutSeconds,
                    maxConnections,
                    maxConnectionsPerRoute,
                    printResponse
            );
        }

        private static void printUsageAndExit() {
            System.out.println("""
                    Usage:
                      javac scripts/LocalRerankLoadTest.java
                      java -cp scripts LocalRerankLoadTest [options]

                    Options:
                      --url=<url>              default: http://172.31.73.27/jina_like_rerank/rerank
                      --token=<token>          default: cffex-bhckgfzs31uadgek
                      --duration=<seconds>     default: 1
                      --concurrency=<n>        default: 18
                      --timeout=<seconds>      default: 60
                      --max-connections=<n>    default: 64
                      --max-connections-per-route=<n>
                                                default: 32
                      --print-response         print full response body

                    Examples:
                      java -cp "<classpath>" LocalRerankLoadTest
                      java -cp "<classpath>" LocalRerankLoadTest --duration=10 --concurrency=54 --max-connections=200 --max-connections-per-route=100
                    """);
            System.exit(0);
        }
    }
}
