import { api, byId, message, methods, money, statusBadge } from './shared.js';

const form = byId('payment-form');
const options = byId('payment-options');
const feedback = byId('checkout-message');
const submit = byId('pay-button');
const confirm = byId('confirm-pix');
const id = window.location.pathname.split('/')[2];
const path = '/' + id;
let pendingPix;
let priorRequest;
let busy = false;

function selectedMethod() {
  return form.elements.namedItem('method').value;
}

function syncMethod() {
  const method = selectedMethod();
  byId('card-options').hidden = method === 'PIX';
  submit.textContent = method === 'PIX' ? 'Gerar Pix de teste' : 'Simular pagamento';
  byId('pix-panel').hidden = !pendingPix || method !== 'PIX';
  submit.hidden = Boolean(pendingPix && method === 'PIX');
}

function show({ charge, attempts }) {
  byId('checkout-description').textContent = charge.description;
  byId('checkout-amount').textContent = money(charge.amountCents);
  byId('checkout-amount').hidden = false;
  statusBadge(byId('checkout-status'), charge.status);
  byId('checkout-status').hidden = false;
  const isOpen = charge.status === 'OPEN';
  form.hidden = !isOpen;
  byId('closed-panel').hidden = isOpen;
  pendingPix = isOpen ? attempts.find(a => a.method === 'PIX' && a.status === 'PENDING') : undefined;
  if (isOpen) {
    syncMethod();
    return;
  }
  byId('pix-panel').hidden = true;
  const approved = attempts.find(a => a.status === 'APPROVED');
  byId('closed-title').textContent = charge.status === 'PAID' ? 'Pagamento confirmado' : 'Cobrança expirada';
  byId('closed-description').textContent = charge.status === 'PAID'
    ? 'Pagamento de teste via ' + methods[approved.method] + ' confirmado.'
    : 'Este link encerrou. Solicite uma nova cobrança ao lojista.';
}

async function load() {
  show(await api(path));
}

function setBusy(value) {
  busy = value;
  options.disabled = value;
  byId('scenario').disabled = value;
  submit.disabled = value;
  confirm.disabled = value;
}

form.addEventListener('change', () => {
  message(feedback);
  syncMethod();
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy) return;
  const method = selectedMethod();
  const scenario = method === 'PIX' ? 'APPROVE' : byId('scenario').value;
  if (method === 'PIX' && pendingPix) return;
  setBusy(true);
  message(feedback);
  try {
    if (!priorRequest || priorRequest.method !== method || priorRequest.scenario !== scenario) {
      priorRequest = { id: crypto.randomUUID(), method, scenario };
    }
    const attempt = await api(path + '/attempts', { method: 'POST', body: priorRequest });
    await load();
    priorRequest = undefined;
    if (attempt.status === 'DECLINED') {
      message(feedback, 'Cartão recusado na simulação. Você pode tentar novamente ou escolher Pix.');
    }
  } catch (error) {
    if (error.status === 409) await load().catch(() => {});
    message(feedback, error.message);
  } finally {
    setBusy(false);
  }
});

confirm.addEventListener('click', async () => {
  if (busy || !pendingPix) return;
  setBusy(true);
  message(feedback);
  try {
    await api(path + '/attempts/' + pendingPix.id + '/confirm', { method: 'POST', body: {} });
    await load();
  } catch (error) {
    if (error.status === 409) await load().catch(() => {});
    message(feedback, error.message);
  } finally {
    setBusy(false);
  }
});

if (id && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id)) {
  load().catch(error => {
    byId('checkout-description').textContent = 'Cobrança indisponível';
    message(feedback, error.message);
  });
} else {
  byId('checkout-description').textContent = 'Cobrança indisponível';
  message(feedback, 'Confira o link de pagamento.');
}
