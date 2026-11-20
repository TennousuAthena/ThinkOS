/** Fixed API 10 vocabulary; extending it requires reviewing the production schema. */
export const RENDER_VOCABULARY = {
  layouts: ['stacks', 'focus', 'form', 'split', 'stage', 'tiles', 'aero', 'panel'],
  components: ['text', 'icon', 'clock', 'form', 'progress', 'chart', 'list', 'daylight', 'moon', 'image', 'carousel', 'controls', 'video', 'seekbar', 'nowplaying', 'tv', 'tabs', 'slider', 'toggle', 'grid'],
  templates: ['glance', 'tile'],
} as const;
