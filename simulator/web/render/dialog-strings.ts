import { localeCandidates } from '../locale-time.ts';

/** Android uses framework OK/Cancel labels; callers may override them with system strings. */
const CONFIRM: Record<string, string> = { en: 'OK', 'zh-Hans': '确定', 'zh-Hant': '確定', hi: 'ठीक है', es: 'Aceptar', fr: 'OK', ar: 'موافق', bn: 'ঠিক আছে', pt: 'OK', ru: 'ОК', ur: 'ٹھیک ہے' };
const CANCEL: Record<string, string> = { en: 'Cancel', 'zh-Hans': '取消', 'zh-Hant': '取消', hi: 'रद्द करें', es: 'Cancelar', fr: 'Annuler', ar: 'إلغاء', bn: 'বাতিল', pt: 'Cancelar', ru: 'Отмена', ur: 'منسوخ کریں' };
export function dialogString(locale: string, strings: Record<string, Record<string, string>> | undefined, action: 'confirm' | 'cancel'): string {
  const candidates = localeCandidates(locale);
  for (const candidate of candidates) {
    const value = strings?.[candidate]?.[action === 'confirm' ? 'form_confirm' : 'plugins_cancel'];
    if (typeof value === 'string') return value;
    const fallback = (action === 'confirm' ? CONFIRM : CANCEL)[candidate]; if (fallback) return fallback;
  }
  return action === 'confirm' ? 'OK' : 'Cancel';
}
