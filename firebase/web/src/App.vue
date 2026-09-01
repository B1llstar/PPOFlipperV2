<script setup>
import { computed, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { useAccounts } from '@/composables/useAccounts'
import { usePresence } from '@/composables/usePresence'
import AccountPicker from '@/components/AccountPicker.vue'
import PresenceBadge from '@/components/PresenceBadge.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'

const route = useRoute()
const { user, authReady, authError, signingIn, signInWithGoogle, signOut, isLikelyAllowlisted } = useAuth()
const { accounts, loading: accountsLoading, error: accountsError, selectedAccountHash, discoverAccounts, selectAccount } =
  useAccounts()
const { isOnline, lastSeenMillis } = usePresence(selectedAccountHash)

const isPermissionDenied = computed(() => accountsError.value?.code === 'permission-denied')

watch(
  user,
  (u) => {
    if (u) discoverAccounts()
  },
  { immediate: true },
)

const navItems = [
  { to: '/', label: 'Dashboard' },
  { to: '/history', label: 'Trade History' },
  { to: '/performance', label: 'Performance' },
]
</script>

<template>
  <div class="min-h-screen bg-[var(--color-base)] text-[var(--color-text)] flex flex-col">
    <!-- Not yet resolved whether a user is signed in -->
    <div v-if="!authReady" class="flex-1 flex items-center justify-center">
      <LoadingSpinner label="Connecting…" />
    </div>

    <!-- Signed out: sign-in screen -->
    <div v-else-if="!user" class="flex-1 flex items-center justify-center px-4">
      <div class="w-full max-w-sm rounded-2xl border border-[var(--color-border)] bg-[var(--color-surface)] p-8 flex flex-col items-center gap-6 text-center">
        <div class="flex flex-col gap-1">
          <span class="text-2xl font-bold tracking-tight">
            PPO<span class="text-[var(--color-accent)]">Flipper</span>Star
          </span>
          <span class="text-sm text-[var(--color-text-dim)]">Live Grand Exchange flipping dashboard</span>
        </div>

        <button
          class="w-full flex items-center justify-center gap-3 rounded-lg bg-[var(--color-surface-3)] border border-[var(--color-border-strong)] px-4 py-2.5 text-sm font-medium hover:border-[var(--color-accent)] hover:bg-[var(--color-surface-2)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="signingIn"
          @click="signInWithGoogle"
        >
          <svg width="18" height="18" viewBox="0 0 18 18">
            <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62Z" />
            <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.81.54-1.85.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18Z" />
            <path fill="#FBBC05" d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.05l3.01-2.33Z" />
            <path fill="#EA4335" d="M9 3.58c1.32 0 2.51.46 3.44 1.35l2.59-2.59C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58Z" />
          </svg>
          {{ signingIn ? 'Signing in…' : 'Sign in with Google' }}
        </button>

        <p v-if="authError" class="text-xs text-[var(--color-loss)]">{{ authError.message }}</p>
        <p class="text-xs text-[var(--color-text-faint)]">
          Access is restricted to allowlisted accounts. This dashboard is read-only — it cannot place, modify, or
          cancel any orders.
        </p>
      </div>
    </div>

    <!-- Signed in but not on the allowlist -->
    <div v-else-if="user && !isLikelyAllowlisted(user)" class="flex-1 flex items-center justify-center px-4">
      <div class="w-full max-w-md flex flex-col gap-4">
        <ErrorState permission-denied />
        <div class="flex items-center justify-center gap-3 text-xs text-[var(--color-text-dim)]">
          <span>Signed in as {{ user.email }}</span>
          <button class="underline hover:text-[var(--color-text)]" @click="signOut">Sign out</button>
        </div>
      </div>
    </div>

    <!-- Signed in, allowlisted (client-side check) but Firestore still denied it (allowlist drift) -->
    <div v-else-if="isPermissionDenied" class="flex-1 flex items-center justify-center px-4">
      <div class="w-full max-w-md flex flex-col gap-4">
        <ErrorState permission-denied />
        <div class="flex items-center justify-center gap-3 text-xs text-[var(--color-text-dim)]">
          <span>Signed in as {{ user.email }}</span>
          <button class="underline hover:text-[var(--color-text)]" @click="signOut">Sign out</button>
        </div>
      </div>
    </div>

    <!-- Fully authorized -->
    <template v-else>
      <header class="border-b border-[var(--color-border)] bg-[var(--color-surface)]/60 backdrop-blur sticky top-0 z-20">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 flex items-center justify-between h-16 gap-4">
          <div class="flex items-center gap-8 min-w-0">
            <span class="text-lg font-bold tracking-tight whitespace-nowrap">
              PPO<span class="text-[var(--color-accent)]">Flipper</span>Star
            </span>
            <nav class="hidden sm:flex items-center gap-1">
              <RouterLink
                v-for="item in navItems"
                :key="item.to"
                :to="item.to"
                class="px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
                :class="
                  route.path === item.to
                    ? 'bg-[var(--color-surface-3)] text-[var(--color-text)]'
                    : 'text-[var(--color-text-dim)] hover:text-[var(--color-text)] hover:bg-[var(--color-surface-2)]'
                "
              >
                {{ item.label }}
              </RouterLink>
            </nav>
          </div>

          <div class="flex items-center gap-4 shrink-0">
            <PresenceBadge v-if="selectedAccountHash" :is-online="isOnline" :last-seen-millis="lastSeenMillis" />
            <AccountPicker
              :accounts="accounts"
              :selected-account-hash="selectedAccountHash"
              @select="selectAccount"
            />
            <div class="flex items-center gap-2">
              <img
                v-if="user.photoURL"
                :src="user.photoURL"
                referrerpolicy="no-referrer"
                class="w-7 h-7 rounded-full"
                :alt="user.email"
              />
              <button
                class="text-xs text-[var(--color-text-dim)] hover:text-[var(--color-text)] underline underline-offset-2"
                @click="signOut"
              >
                Sign out
              </button>
            </div>
          </div>
        </div>
        <nav class="sm:hidden flex items-center gap-1 px-4 pb-2 overflow-x-auto">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="px-3 py-1.5 rounded-lg text-sm font-medium whitespace-nowrap transition-colors"
            :class="
              route.path === item.to
                ? 'bg-[var(--color-surface-3)] text-[var(--color-text)]'
                : 'text-[var(--color-text-dim)]'
            "
          >
            {{ item.label }}
          </RouterLink>
        </nav>
      </header>

      <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 py-6">
        <LoadingSpinner v-if="accountsLoading" label="Discovering accounts…" />
        <ErrorState
          v-else-if="accountsError"
          title="Failed to discover accounts"
          :message="accountsError.message"
        />
        <div
          v-else-if="accounts.length === 0"
          class="flex flex-col items-center justify-center gap-2 py-24 text-center"
        >
          <span class="text-3xl text-[var(--color-text-faint)]">◇</span>
          <p class="text-sm font-medium text-[var(--color-text-dim)]">No account found</p>
          <p class="text-xs text-[var(--color-text-faint)] max-w-sm">
            No PPOFlipperStar account has ever sent a presence heartbeat to this Firebase project. Launch the
            RuneLite plugin with Firestore sync enabled to see it appear here.
          </p>
        </div>
        <RouterView v-else :account-hash="selectedAccountHash" />
      </main>
    </template>
  </div>
</template>
