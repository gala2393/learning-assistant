import { ArrowLeft, Mail } from 'lucide-react'
import { Link } from 'react-router-dom'

const LAST_UPDATED = '2026-06-17'
const CONTACT_EMAIL = '2393158856@qq.com'

type LegalSection = {
  title: string
  paragraphs: string[]
}

type LegalPageProps = {
  eyebrow: string
  title: string
  description: string
  sections: LegalSection[]
}

function LegalPageLayout({ eyebrow, title, description, sections }: LegalPageProps) {
  return (
    <main className="min-h-screen bg-[#f7f9fb] px-5 py-6 text-[#222833] sm:px-8 lg:px-10">
      <div className="mx-auto max-w-4xl">
        <nav className="mb-8 flex items-center justify-between gap-4">
          <Link
            to="/"
            className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-bold text-slate-700 shadow-sm transition hover:border-slate-300 hover:text-slate-950"
          >
            <ArrowLeft className="h-4 w-4" />
            返回首页
          </Link>
          <span className="text-sm font-bold text-slate-500">智学引擎</span>
        </nav>

        <header className="border-b border-slate-200 pb-8">
          <p className="text-sm font-black tracking-[0.18em] text-slate-500">{eyebrow}</p>
          <h1 className="mt-4 text-4xl font-black leading-tight tracking-normal text-[#222833] sm:text-5xl">{title}</h1>
          <p className="mt-5 max-w-3xl text-base font-medium leading-8 text-slate-600">{description}</p>
          <p className="mt-4 text-sm font-bold text-slate-500">最后更新：{LAST_UPDATED}</p>
        </header>

        <article className="space-y-8 py-8">
          {sections.map((section) => (
            <section key={section.title} className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
              <h2 className="text-xl font-black text-[#222833]">{section.title}</h2>
              <div className="mt-4 space-y-3 text-sm leading-7 text-slate-600">
                {section.paragraphs.map((paragraph) => (
                  <p key={paragraph}>{paragraph}</p>
                ))}
              </div>
            </section>
          ))}
        </article>
      </div>
    </main>
  )
}

export function TermsPage() {
  return (
    <LegalPageLayout
      eyebrow="TERMS"
      title="使用协议"
      description="本协议用于说明你在访问和使用智学引擎时应遵守的基本规则。使用本服务即表示你理解并同意以下内容。"
      sections={[
        {
          title: '一、服务说明',
          paragraphs: [
            '智学引擎提供学习资料上传、资料解析、阅读、AI 问答、资料检索、知识总结、历史记录和收藏等学习辅助功能。',
            '平台生成的回答基于用户输入、已上传资料、检索片段和模型能力形成，仅作为学习参考，不构成法律、医疗、金融、考试作弊或其他专业决策建议。',
          ],
        },
        {
          title: '二、账号与安全',
          paragraphs: [
            '你应妥善保管账号、密码和登录状态，不得将账号提供给他人进行违法、侵权或破坏平台稳定性的行为。',
            '如发现账号异常使用、资料泄露风险或未授权登录，请及时通过本协议中的联系方式与我们联系。',
          ],
        },
        {
          title: '三、用户资料与内容',
          paragraphs: [
            '你上传的文档、图片、问答内容、临时资料和相关元数据应当来源合法，不得侵犯他人的版权、隐私、商业秘密或其他合法权益。',
            '你不得上传违法违规、恶意代码、病毒文件、攻击脚本、暴力恐怖、淫秽色情、诈骗引流、侵犯个人信息或其他不适宜内容。',
            '为了提供资料解析、检索和问答功能，系统可能会对你上传的资料进行文本提取、切片、索引、向量化和缓存处理。',
          ],
        },
        {
          title: '四、AI 输出限制',
          paragraphs: [
            'AI 输出可能存在遗漏、误解、过时或不准确的情况。你应结合原文依据和自己的判断进行核验。',
            '当输入内容过长、资料解析失败、模型服务异常或网络波动时，回答可能被截断、失败或延迟。平台会尽量提供明确的错误提示和状态反馈。',
          ],
        },
        {
          title: '五、禁止行为',
          paragraphs: [
            '不得通过爬虫、批量注册、接口滥用、绕过限流、逆向工程、攻击服务器或其他方式影响平台安全与稳定。',
            '不得利用平台生成、传播违法违规内容，或诱导模型输出危害网络安全、侵犯隐私、作弊代写、欺诈等内容。',
          ],
        },
        {
          title: '六、服务变更与免责声明',
          paragraphs: [
            '我们可能根据产品迭代、合规要求或系统维护调整部分功能、接口、容量限制和展示方式。',
            '因不可抗力、第三方服务故障、网络异常、模型服务异常、用户误操作或资料本身质量问题导致的服务中断或结果偏差，我们将在合理范围内协助处理。',
          ],
        },
        {
          title: '七、联系我们',
          paragraphs: [`如你对本协议或平台使用有疑问，可通过邮箱 ${CONTACT_EMAIL} 联系我们。`],
        },
      ]}
    />
  )
}

export function PrivacyPage() {
  return (
    <LegalPageLayout
      eyebrow="PRIVACY"
      title="隐私政策"
      description="本政策说明智学引擎如何收集、使用、存储和保护与你使用服务相关的信息。"
      sections={[
        {
          title: '一、我们收集的信息',
          paragraphs: [
            '账号信息：包括注册、登录、身份校验所需的账号标识、昵称、角色、登录时间等信息。',
            '学习内容：包括你上传的资料、解析后的文本片段、页面预览、问答记录、临时资料、收藏、总结和使用记录。',
            '设备与日志：包括请求时间、接口路径、错误日志、浏览器信息、IP 地址、性能状态等用于安全审计和问题排查的信息。',
          ],
        },
        {
          title: '二、信息使用目的',
          paragraphs: [
            '用于完成资料解析、资料阅读、检索增强问答、历史恢复、继续阅读、总结生成、后台管理和安全风控等核心功能。',
            '用于定位上传失败、解析卡住、回答错误、页面加载异常、账户异常访问等问题，并持续改进产品体验。',
          ],
        },
        {
          title: '三、资料处理与第三方服务',
          paragraphs: [
            '为了实现 AI 问答和向量检索，系统可能会将必要的文本片段、问题和上下文发送给配置的模型服务或向量检索服务处理。',
            '我们会尽量只传输完成当前功能所需的最小必要内容，并通过配置、权限和访问控制降低非必要暴露风险。',
          ],
        },
        {
          title: '四、存储与安全',
          paragraphs: [
            '我们会采用访问控制、日志审计、文件隔离、数据库权限和必要的传输安全措施保护你的资料和账号信息。',
            '请理解任何互联网服务都无法保证绝对安全。如发生安全事件，我们会在合理范围内采取补救措施并按适用要求通知相关用户。',
          ],
        },
        {
          title: '五、你的权利',
          paragraphs: [
            '你可以在平台内查看、删除自己上传的资料和相关学习记录。删除资料后，系统会尽力清理与该资料相关的页面、片段、索引和缓存。',
            '如需进一步查询、更正或删除账号相关信息，可通过本政策中的联系方式向我们提出请求。',
          ],
        },
        {
          title: '六、未成年人使用',
          paragraphs: [
            '未成年人应在监护人指导下使用本服务，不应上传包含本人或他人敏感个人信息、隐私信息或无授权学习资料的内容。',
          ],
        },
        {
          title: '七、联系我们',
          paragraphs: [`如你对隐私保护、资料处理或账号信息有疑问，可通过邮箱 ${CONTACT_EMAIL} 联系我们。`],
        },
      ]}
    />
  )
}

export function AboutPage() {
  return (
    <main className="min-h-screen bg-[#f7f9fb] px-5 py-6 text-[#222833] sm:px-8 lg:px-10">
      <div className="mx-auto max-w-4xl">
        <nav className="mb-8 flex items-center justify-between gap-4">
          <Link
            to="/"
            className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-bold text-slate-700 shadow-sm transition hover:border-slate-300 hover:text-slate-950"
          >
            <ArrowLeft className="h-4 w-4" />
            返回首页
          </Link>
          <span className="text-sm font-bold text-slate-500">智学引擎</span>
        </nav>

        <section className="rounded-lg border border-slate-200 bg-white p-7 shadow-sm sm:p-10">
          <p className="text-sm font-black tracking-[0.18em] text-slate-500">ABOUT</p>
          <h1 className="mt-4 text-4xl font-black leading-tight tracking-normal text-[#222833] sm:text-5xl">关于我们</h1>
          <p className="mt-5 text-base font-medium leading-8 text-slate-600">
            智学引擎是面向学习场景的资料管理与 AI 问答工具，目标是把分散的 PDF、Word、TXT、Markdown 等学习资料整理成可检索、可追问、可复习的知识空间。
          </p>
          <p className="mt-4 text-base font-medium leading-8 text-slate-600">
            我们重视资料来源、原文依据和学习过程的连续性，希望用户在阅读、提问、总结和复盘时，能更快回到材料本身，而不是只得到一段不可追溯的回答。
          </p>

          <div className="mt-8 rounded-lg bg-[#f7f9fb] p-5">
            <h2 className="text-xl font-black text-[#222833]">联系方式</h2>
            <a
              className="mt-4 inline-flex items-center gap-2 text-sm font-black text-slate-700 transition hover:text-slate-950 hover:underline"
              href={`mailto:${CONTACT_EMAIL}`}
            >
              <Mail className="h-4 w-4" />
              {CONTACT_EMAIL}
            </a>
          </div>

          <p className="mt-6 text-sm font-bold text-slate-500">最后更新：{LAST_UPDATED}</p>
        </section>
      </div>
    </main>
  )
}
