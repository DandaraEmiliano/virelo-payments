import { api, byId, date, message, methods, money, statusBadge } from './shared.js';

const createDialog = byId('charge-dialog');
const detailDialog = byId('detail-dialog');
const rows = byId('charge-rows');
const listMessage = byId('dashboard-message');
const detailContent = byId('detail-content');
let listVersion = 0;
let detailVersion = 0;

function renderList(charges) {
  const totals = { PAID: 0, OPEN: 0, EXPIRED: 0 };
  const fragment = document.createDocumentFragment();
  for (const charge of charges) {
    totals[charge.status] += charge.amountCents;
    const row = byId('charge-row').content.cloneNode(true);
    row.querySelector('.row-amount').textContent = money(charge.amountCents);
    row.querySelector('.row-date').textContent = date(charge.createdAt);
    statusBadge(row.querySelector('.status'), charge.status);
    const name = row.querySelector('.charge-name');
    const details = row.querySelector('.button-quiet');
    name.textContent = charge.description;
    details.setAttribute('aria-label', 'Ver detalhes de ' + charge.description);
    for (const button of [name, details]) {
      button.addEventListener('click', () => openDetails(charge.id));
    }
    fragment.append(row);
  }
  rows.replaceChildren(fragment);
  byId('paid-total').textContent = money(totals.PAID);
  byId('open-total').textContent = money(totals.OPEN);
  byId('expired-total').textContent = money(totals.EXPIRED);
  byId('charge-count').textContent = charges.length === 1 ? '1 cobrança' : charges.length + ' cobranças';
  byId('empty-list').hidden = charges.length > 0;
}

async function refresh() {
  const version = ++listVersion;
  const refreshButton = byId('refresh');
  refreshButton.disabled = true;
  message(listMessage);
  try {
    const charges = await api('');
    if (version === listVersion) renderList(charges);
  } catch (error) {
    if (version !== listVersion) return;
    if (rows.children.length === 0) byId('charge-count').textContent = 'Cobranças indisponíveis';
    message(listMessage, error.message);
  } finally {
    if (version === listVersion) refreshButton.disabled = false;
  }
}

async function openDetails(id) {
  const version = ++detailVersion;
  detailContent.hidden = true;
  message(byId('details-error'), 'Carregando detalhes…', 'info');
  detailDialog.showModal();
  try {
    const { charge, attempts } = await api('/' + id);
    if (version !== detailVersion || !detailDialog.open) return;
    const url = new URL('/checkout/' + charge.id, window.location.origin);
    byId('share-url').value = url.href;
    byId('open-checkout').href = url.href;
    byId('copy-link').textContent = 'Copiar';
    byId('detail-description').textContent = charge.description;
    byId('detail-amount').textContent = money(charge.amountCents);
    statusBadge(byId('detail-status'), charge.status);
    const expiration = byId('detail-expiration');
    expiration.hidden = charge.status === 'PAID';
    expiration.textContent = (charge.status === 'EXPIRED' ? 'Expirou em ' : 'Expira em ') + date(charge.expiresAt);
    byId('empty-attempts').hidden = attempts.length !== 0;
    byId('attempts').replaceChildren(...attempts.map(attempt => {
      const item = document.createElement('li');
      const text = document.createElement('div');
      const name = document.createElement('strong');
      const time = document.createElement('small');
      const status = document.createElement('span');
      name.textContent = methods[attempt.method];
      time.textContent = date(attempt.updatedAt);
      statusBadge(status, attempt.status);
      text.append(name, time);
      item.append(text, status);
      return item;
    }));
    detailContent.hidden = false;
    message(byId('details-error'));
  } catch (error) {
    if (version === detailVersion && detailDialog.open) message(byId('details-error'), error.message);
  }
}

function parseCents(value) {
  const match = /^([0-9]{1,6})(?:[,.]([0-9]{1,2}))?$/.exec(value.trim());
  if (!match) return 0;
  return Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
}

for (const button of document.querySelectorAll('[data-close]')) {
  button.addEventListener('click', () => button.closest('dialog').close());
}

byId('new-charge').addEventListener('click', () => {
  message(byId('create-error'));
  createDialog.showModal();
});
byId('refresh').addEventListener('click', refresh);
window.addEventListener('focus', refresh);

function updateNavigation() {
  const current = location.hash === '#cobrancas' ? '#cobrancas' : '#visao-geral';
  for (const item of document.querySelectorAll('.nav-item')) {
    if (item.hash === current) item.setAttribute('aria-current', 'location');
    else item.removeAttribute('aria-current');
  }
}
window.addEventListener('hashchange', updateNavigation);
updateNavigation();

byId('new-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const description = byId('description').value.trim();
  const amountCents = parseCents(byId('amount').value);
  if (!description || !amountCents) {
    message(byId('create-error'), 'Preencha a descrição e informe um valor válido, como 89,90.');
    return;
  }
  const button = byId('create-button');
  button.disabled = true;
  message(byId('create-error'));
  try {
    const charge = await api('', { method: 'POST', body: { description, amountCents } });
    form.reset();
    createDialog.close();
    void refresh();
    await openDetails(charge.id);
  } catch (error) {
    message(byId('create-error'), error.message);
  } finally {
    button.disabled = false;
  }
});
byId('copy-link').addEventListener('click', async () => {
  const input = byId('share-url');
  try {
    await navigator.clipboard.writeText(input.value);
    byId('copy-link').textContent = 'Copiado';
  } catch {
    input.select();
    byId('copy-link').textContent = 'Link selecionado';
  }
});
void refresh();
