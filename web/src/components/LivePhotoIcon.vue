<template>
  <!-- iOS "Live Photo"-style glyph: a solid center dot, a thin ring, and an
       outer dashed ring. Uses currentColor so callers control the color. -->
  <svg
    class="live-photo-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <circle cx="12" cy="12" r="3.1" fill="currentColor" stroke="none" />
    <circle cx="12" cy="12" r="6" stroke-width="1.4" />
    <circle
      cx="12"
      cy="12"
      r="9.3"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-dasharray="0.2 3.1"
    />
  </svg>
</template>

<script setup lang="ts"></script>

<style scoped>
.live-photo-icon {
  width: 1em;
  height: 1em;
  display: block;
}
</style>
