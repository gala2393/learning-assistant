/**
 * action-button-styles - 操作按钮统一样式常量
 *
 * 功能说明：
 * - 定义了三种按钮状态的 Tailwind CSS 类名
 * - 用于统一项目中各表单提交按钮的视觉风格
 *
 * 使用方式：
 * - actionButtonBase：基础样式（始终应用）
 * - actionButtonIdle：未就绪状态（表单未填完时）
 * - actionButtonReady：就绪状态（表单可提交时）
 *
 * 示例：
 *   className={`${actionButtonBase} ${canSubmit ? actionButtonReady : actionButtonIdle}`}
 */

// 基础样式：白色文字 + 过渡动画，禁用时保持 100% 透明度
export const actionButtonBase =
  'text-white transition-all duration-200 disabled:opacity-100'

// 未就绪状态：浅灰色背景，无阴影，鼠标悬停时颜色稍深
export const actionButtonIdle =
  'bg-[#d8dde3] shadow-none hover:bg-[#cbd2da]'

// 就绪状态：深灰背景 + 投影，悬停时轻微上浮并加深颜色
export const actionButtonReady =
  'bg-[#4b5563] shadow-[0_10px_22px_rgba(75,85,99,0.24)] hover:-translate-y-0.5 hover:bg-[#374151] hover:shadow-[0_14px_26px_rgba(55,65,81,0.28)]'
