const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dateTime = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });

export const money = (cents) => currency.format(cents / 100);
export const date = (instant) => dateTime.format(new Date(instant));
export const byId = (id) => document.getElementById(id);

export const methods = { PIX: 'Pix', CREDIT: 'Cartão de crédito', DEBIT: 'Cartão de débito' };
export const labels = {
  OPEN: 'Pendente', PAID: 'Pago', EXPIRED: 'Expirado',
  PENDING: 'Aguardando', APPROVED: 'Aprovado', DECLINED: 'Recusado', SUPERSEDED: 'Encerrado'
};

export function statusBadge(element, status) {
  element.className = 'status status-' + status.toLowerCase();
  element.textContent = labels[status];
}

export function message(element, text = '', type = 'error') {
  element.hidden = !text;
  element.textContent = text;
  element.dataset.type = type;
}

export async function api(path, options = {}) {
  const { body, method = 'GET' } = options;
  const response = await fetch('/api/charges' + path, {
    method,
    cache: 'no-store',
    headers: body === undefined ? { Accept: 'application/json' } : {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  }).catch(() => {
    throw new Error('Não foi possível conectar. Tente novamente.');
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const error = new Error(response.status >= 500
      ? 'O serviço está temporariamente indisponível. Tente novamente.'
      : problem?.detail || 'Não foi possível concluir esta operação.');
    error.status = response.status;
    throw error;
  }
  return response.json();
}
