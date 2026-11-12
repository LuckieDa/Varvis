module.exports = {
  extends: ['stylelint-config-standard'],
  ignoreFiles: ['dist/**', 'src/**/*.less', 'src/**/*.scss'],
  rules: {
    'no-empty-source': null,
    'color-hex-length': null,
    'font-family-no-missing-generic-family-keyword': null,
    'selector-class-pattern': null,
    'value-keyword-case': null,
  },
};
